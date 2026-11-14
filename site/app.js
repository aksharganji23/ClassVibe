// Fills in the latest version, size and links from the GitHub Releases API.
// Works for any fork: owner/repo come from the Pages URL (owner.github.io/repo).
(function () {
  var owner = "aksharganji23", repo = "ClassVibe";
  var host = location.hostname;
  if (host.endsWith(".github.io")) {
    owner = host.replace(".github.io", "");
    var first = location.pathname.split("/").filter(Boolean)[0];
    if (first) repo = first;
  }
  var base = "https://github.com/" + owner + "/" + repo;
  var direct = base + "/releases/latest/download/ClassVibe.apk";
  document.getElementById("download").href = direct;
  document.getElementById("notes").href = base + "/releases/latest";
  document.getElementById("repo-link").href = base;

  fetch("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (rel) {
      if (!rel || !rel.tag_name) return;
      var assets = rel.assets || [];
      var apk = assets.filter(function (a) { return a.name === "ClassVibe.apk"; })[0] ||
        assets.filter(function (a) { return /\.apk$/.test(a.name); })[0];
      if (apk) document.getElementById("download").href = apk.browser_download_url;
      var mb = apk ? " • " + (apk.size / 1048576).toFixed(1) + " MB" : "";
      var date = rel.published_at ? " • " + new Date(rel.published_at).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : "";
      document.getElementById("download").textContent = "⬇️ Download " + rel.tag_name;
      document.getElementById("meta").textContent = "Free • Android 8.0+" + mb + date + " • No accounts, ads or trackers";
      document.getElementById("ver").textContent = "Latest: " + rel.tag_name;
    })
    .catch(function () { /* offline or rate-limited: the static links still work */ });
})();
