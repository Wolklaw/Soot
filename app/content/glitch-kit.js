/* wolklaw glitch kit (vanilla, no deps). Put before </body>.
   Markup: <h1 class="glitch">Title</h1>   <div class="screen glitch-box">...</div>
   Rhythm: h1 tears 450 ms after load and every 7-12 s; other .glitch/.glitch-box tear every 7-12 s while visible;
   headings tear once when scrolled into view. Call window.glitch(el) on events (result, error, submit, tab change, delete). */
(function () {
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  var rand = function (a, b) { return a + Math.random() * (b - a); };
  function burst(el) {
    if (reduce || !el) return;
    el.classList.remove('is-burst'); void el.offsetWidth; el.classList.add('is-burst');
    setTimeout(function () { el.classList.remove('is-burst'); }, 360);
  }
  window.glitch = burst;
  var all = [].slice.call(document.querySelectorAll('.glitch, .glitch-box'));
  all.forEach(function (el) {
    if (el.classList.contains('glitch')) el.setAttribute('data-text', el.textContent.replace(/\s+/g, ' ').trim());
  });
  function loop(el, first) {
    setTimeout(function () { if (!document.hidden) burst(el); loop(el, false); }, first ? rand(2500, 5000) : rand(7000, 12000));
  }
  all.forEach(function (el) {
    if (el.tagName === 'H1') { setTimeout(function () { burst(el); }, 450); loop(el, false); } else loop(el, true);
  });
  /* click feedback on every button: tear the nearest box */
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('button, .btn');
    if (b && b.hasAttribute('data-glitch-click')) burst(b.closest('.glitch-box') || b);
  });
})();
