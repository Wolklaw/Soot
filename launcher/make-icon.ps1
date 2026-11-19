# Draws Soot's mark (an S with the channel slip) and writes every icon the browser needs:
# launcher\soot.ico, the in-browser logos under app\content\brand, and the new tab icon.
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$brand = Join-Path $root 'app\content\brand'
New-Item -ItemType Directory -Force $brand | Out-Null
$letter = 'S'
$family = 'Consolas'
foreach ($f in (New-Object System.Drawing.Text.InstalledFontCollection).Families) { if ($f.Name -eq 'Cascadia Mono') { $family = 'Cascadia Mono' } }

function New-Mark([int]$s) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $r = [Math]::Max(1, [int]($s * 0.09)); $d = $r * 2; $w = $s - 1
    $box = New-Object System.Drawing.Drawing2D.GraphicsPath
    $box.AddArc(0, 0, $d, $d, 180, 90); $box.AddArc($w - $d, 0, $d, $d, 270, 90)
    $box.AddArc($w - $d, $w - $d, $d, $d, 0, 90); $box.AddArc(0, $w - $d, $d, $d, 90, 90); $box.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 10, 21, 23))), $box)
    $g.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 31, 106, 102)), ([Math]::Max(1, $s / 64))), $box)
    # the letter as an outline, scaled to fill the box, drawn in magenta, cyan, then the accent
    $glyph = New-Object System.Drawing.Drawing2D.GraphicsPath
    $glyph.AddString($letter, (New-Object System.Drawing.FontFamily $family), 1, 100, (New-Object System.Drawing.PointF 0, 0), [System.Drawing.StringFormat]::GenericTypographic)
    $b = $glyph.GetBounds()
    $k = ($s * 0.62) / $b.Height
    $m = New-Object System.Drawing.Drawing2D.Matrix
    $m.Translate((($s - $b.Width * $k) / 2), (($s - $b.Height * $k) / 2)); $m.Scale($k, $k); $m.Translate(-$b.X, -$b.Y)
    $glyph.Transform($m)
    $slip = [Math]::Max(1, $s * 0.04)
    foreach ($layer in @(@(255, 46, 136, $slip), @(34, 216, 255, (-$slip)), @(61, 232, 200, 0))) {
        $p = $glyph.Clone()
        $t = New-Object System.Drawing.Drawing2D.Matrix; $t.Translate($layer[3], 0); $p.Transform($t)
        $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, $layer[0], $layer[1], $layer[2]))), $p)
    }
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return , $ms.ToArray()
}

# in-browser logos
foreach ($s in 16, 32, 48, 64, 128) { [System.IO.File]::WriteAllBytes((Join-Path $brand "icon$s.png"), (New-Mark $s)) }
[System.IO.File]::WriteAllBytes((Join-Path $brand 'about-logo.png'), (New-Mark 192))
[System.IO.File]::WriteAllBytes((Join-Path $brand 'about-logo@2x.png'), (New-Mark 384))
[System.IO.File]::WriteAllBytes((Join-Path $root 'config\chrome\soot.png'), (New-Mark 64))
$b64 = [Convert]::ToBase64String((New-Mark 256))
Set-Content (Join-Path $brand 'about-logo.svg') -Encoding ascii -Value "<svg xmlns=`"http://www.w3.org/2000/svg`" xmlns:xlink=`"http://www.w3.org/1999/xlink`" width=`"256`" height=`"256`" viewBox=`"0 0 256 256`"><image width=`"256`" height=`"256`" xlink:href=`"data:image/png;base64,$b64`"/></svg>"
Set-Content (Join-Path $brand 'wordmark.svg') -Encoding ascii -Value '<svg xmlns="http://www.w3.org/2000/svg" width="172" height="52" viewBox="0 0 172 52"><g font-family="Cascadia Mono, Consolas, monospace" font-weight="700" font-size="44"><text x="4" y="40" fill="#ff2e88">SOOT</text><text x="0" y="40" fill="#22d8ff">SOOT</text><text x="2" y="40" fill="#d4f1ec">SOOT</text></g></svg>'

# the Windows icon for Soot.exe and its shortcuts
$sizes = 256, 64, 48, 32, 24, 16
$pngs = @(); foreach ($s in $sizes) { $pngs += , (New-Mark $s) }
$fs = [System.IO.File]::Create((Join-Path $PSScriptRoot 'soot.ico'))
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $bw.Write([byte]($s % 256)); $bw.Write([byte]($s % 256)); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$pngs[$i].Length); $bw.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Close()
'icons written'
