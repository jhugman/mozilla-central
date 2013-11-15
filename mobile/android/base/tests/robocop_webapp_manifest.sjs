let manifest = {
  name: "Android Test",
  launch_path: "/robocop/robocop_webapp_main.html",
  installs_allowed_from: ["*"],
};

function handleRequest(request, response) {
  let version = parseInt(getState("version") || "0") + 1;
  manifest.version = version.toString();
  setState("version", manifest.version);

  response.setStatusLine(request.httpVersion, 200, "OK");
  response.setHeader("Content-Type", "application/x-web-app-manifest+json", false);
  response.setHeader("Cache-Control", "no-cache", false);
  response.write(JSON.stringify(manifest));
}
