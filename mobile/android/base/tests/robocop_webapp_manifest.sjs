let manifest = {
  name: "Android Test",
  launch_path: "/robocop/robocop_webapp_main.html",
  installs_allowed_from: ["*"],
};

function handleRequest(request, response) {
  let version = (getState("version") || 0) + 1;
  setState("version", version);
  manifest.version = version.toString();

  response.setStatusLine(request.httpVersion, 200, null);
  response.setHeader("Content-Type", "application/x-web-app-manifest+json", false);
  response.setHeader("Cache-Control", "no-cache", false);
  response.write(JSON.stringify(manifest));
}
