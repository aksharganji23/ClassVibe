// Fills in the latest version, size and the versioned APK link (e.g. ClassVibe-v1.3.0.apk).
// Reads version.json (published next to this page after every release); falls back to the
// GitHub API. Works for any fork: owner/repo come from the Pages URL (owner.github.io/repo).
(function () {
  var owner = "aksharganji23", repo = "ClassVibe";
  var host = location.hostname;
  if (host.endsWith(".github.io")) {
    owner = host.replace(".github.io", "");
    var first = location.pathname.split("/").filter(Boolean)[0];
    if (first) repo = first;
  }
  var base = "https://github.com/" + owner + "/" + repo;
  var btn = document.getElementById("download");
  btn.href = base + "/releases/latest";
  document.getElementById("notes").href = base + "/releases/latest";
  document.getElementById("repo-link").href = base;

  function show(r) {
    if (!r || !r.tag) return false;
    btn.href = r.apk_url;
    btn.setAttribute("download", r.apk_name);
    btn.textContent = "⬇️ Download " + r.tag;
    var mb = r.size ? " • " + (r.size / 1048576).toFixed(1) + " MB" : "";
    var date = r.published ? " • " + new Date(r.published).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : "";
    document.getElementById("meta").textContent = "Free • Android 8.0+" + mb + date + " • " + r.apk_name;
    document.getElementById("ver").textContent = "Latest: " + r.tag;
    if (r.page_url) document.getElementById("notes").href = r.page_url;
    return true;
  }

  function fromApi() {
    return fetch("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest")
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (rel) {
        if (!rel) return;
        var apk = (rel.assets || []).filter(function (a) { return /\.apk$/.test(a.name) && a.name !== "ClassVibe.apk"; })[0];
        if (!apk) return;
        show({
          tag: rel.tag_name, apk_url: apk.browser_download_url, apk_name: apk.name,
          size: apk.size, published: rel.published_at, page_url: rel.html_url
        });
      });
  }

  fetch("version.json?t=" + Date.now())
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (v) { if (!(v && show(v.stable))) return fromApi(); })
    .catch(function () { return fromApi().catch(function () {}); });
})();
