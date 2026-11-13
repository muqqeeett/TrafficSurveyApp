/* Native shell shim. Runs before the web app and fills the few gaps a WebView has compared with
   a browser: file downloads, window.print(), pop-up report window, service worker, status bar colour.
   The web app itself (www/index.html) is used unchanged. */
(function () {
  if (window.__tsShim || !window.AndroidBridge) return;
  window.__tsShim = true;
  var B = window.AndroidBridge;

  /* The app is bundled in the APK, so no service worker is needed (and none would be served). */
  try { delete Navigator.prototype.serviceWorker; } catch (e) {}

  /* ---- Downloads: <a download href="blob:..."> and data: URIs ---- */
  function toBase64(blob, cb) {
    var r = new FileReader();
    r.onload = function () { var s = String(r.result), i = s.indexOf(","); cb(s.slice(i + 1), blob.type); };
    r.onerror = function () { B.toast("Could not save the file."); };
    r.readAsDataURL(blob);
  }
  function saveAnchor(a) {
    var href = a.href || "", name = a.getAttribute("download") || "download";
    if (href.indexOf("data:") === 0) {
      var m = /^data:([^;,]*)(;base64)?,(.*)$/s.exec(href);
      if (!m) return false;
      var mime = m[1] || "application/octet-stream";
      if (m[2]) B.saveFile(name, mime, m[3]);
      else B.saveFile(name, mime, btoa(unescape(encodeURIComponent(decodeURIComponent(m[3])))));
      return true;
    }
    if (href.indexOf("blob:") === 0) {
      fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
        toBase64(blob, function (b64, type) { B.saveFile(name, type || "application/octet-stream", b64); });
      }).catch(function () { B.toast("Could not save the file."); });
      return true;
    }
    return false;
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute("download") && saveAnchor(this)) return;
    return origClick.apply(this, arguments);
  };
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a[download]") : null;
    if (a && saveAnchor(a)) e.preventDefault();
  }, true);

  /* ---- Report pop-up: make window.print() open Android's print / Save as PDF dialog ---- */
  var lastPrint = 0;
  function nativePrint() {
    var now = Date.now();
    if (now - lastPrint < 1500) return;
    lastPrint = now;
    B.printPopup();
  }
  var origOpen = window.open;
  window.open = function () {
    var w = origOpen.apply(window, arguments);
    if (w) {
      var tries = 0, iv = setInterval(function () {
        try { if (w.closed || ++tries > 300) { clearInterval(iv); return; } if (w.print !== nativePrint) w.print = nativePrint; }
        catch (e) { clearInterval(iv); }
      }, 50);
    }
    return w;
  };
  window.print = function () { B.printMain(); };

  /* ---- Status / navigation bar follows the app theme (dark / light) ---- */
  function syncBars() {
    var m = document.querySelector('meta[name="theme-color"]');
    if (m && m.content) B.setBarColor(m.content);
  }
  function watch() {
    syncBars();
    var m = document.querySelector('meta[name="theme-color"]');
    if (m) new MutationObserver(syncBars).observe(m, { attributes: true, attributeFilter: ["content"] });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", watch); else watch();
})();
