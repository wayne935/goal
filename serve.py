"""Goal 本機伺服器（start.bat 會執行這個檔案）。

只提供網頁需要的檔案，不提供 data/、inbox/ 等紀錄；
並檢查 Host 標頭，避免其他網站用 DNS rebinding 讀到本機內容。
"""
import http.server
import os
import socketserver

PORT = 8765
ALLOWED_HOSTS = {f"localhost:{PORT}", f"127.0.0.1:{PORT}"}
ALLOWED_FILES = {"/", "/index.html", "/manifest.json", "/sw.js", "/version.json"}
ALLOWED_PREFIXES = ("/icons/",)


class Handler(http.server.SimpleHTTPRequestHandler):
    def _allowed(self):
        if self.headers.get("Host", "") not in ALLOWED_HOSTS:
            return False
        path = self.path.split("?", 1)[0].split("#", 1)[0]
        if ".." in path or "\\" in path:
            return False
        return path in ALLOWED_FILES or (path.startswith(ALLOWED_PREFIXES) and path.endswith(".png"))

    def do_GET(self):
        if not self._allowed():
            self.send_error(404)
            return
        super().do_GET()

    def do_HEAD(self):
        if not self._allowed():
            self.send_error(404)
            return
        super().do_HEAD()

    def end_headers(self):
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
        print(f"Goal is running at http://localhost:{PORT}/  -- close this window to stop.")
        httpd.serve_forever()
