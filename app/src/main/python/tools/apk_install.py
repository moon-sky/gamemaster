"""APK 下载工具：从 URL 流式下载到指定本地路径。

注意：Chaquopy 跑在 app 进程，是 app uid，无法直接写 /data/local/tmp。
入口函数接收 Kotlin 侧传入的缓存目录，下载到该目录后返回路径；
实际的 `pm install` 由 Kotlin 侧通过 BackendSelector.bestForShell()?.exec 调用。

入口函数：download(url, dest_dir) -> str
- url: APK 直链
- dest_dir: 由 Kotlin 侧传入的 cache 目录路径（app private cache 或外存可写目录）
输出：成功返回下载文件的绝对路径字符串；失败返回 "ERROR: <原因>"。
"""
import os
import requests


CHUNK = 64 * 1024  # 64KB / chunk，移动网络更友好
TIMEOUT_CONNECT = 15
TIMEOUT_READ = 60


def download(url: str, dest_dir: str) -> str:
    if not url or not url.strip():
        return "ERROR: url 不能为空"
    if not dest_dir or not os.path.isdir(dest_dir):
        return "ERROR: dest_dir 不可用：{}".format(dest_dir)
    if not (url.startswith("http://") or url.startswith("https://")):
        return "ERROR: url 必须以 http:// 或 https:// 开头"

    # 文件名：URL 末段 + .apk 后缀兜底
    name = url.rsplit("/", 1)[-1].split("?")[0]
    if not name.lower().endswith(".apk"):
        name = "download.apk"
    dest_path = os.path.join(dest_dir, name)
    # 防止同名覆盖：序号自增
    counter = 1
    while os.path.exists(dest_path):
        base, ext = os.path.splitext(name)
        dest_path = os.path.join(dest_dir, "{}_{}{}".format(base, counter, ext))
        counter += 1

    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    }

    try:
        with requests.get(
            url, headers=headers, stream=True,
            timeout=(TIMEOUT_CONNECT, TIMEOUT_READ),
            allow_redirects=True,
        ) as resp:
            resp.raise_for_status()
            total = int(resp.headers.get("Content-Length", "0"))
            written = 0
            with open(dest_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=CHUNK):
                    if chunk:
                        f.write(chunk)
                        written += len(chunk)
            if total > 0 and written < total:
                # 不完整，删掉避免误装
                try:
                    os.remove(dest_path)
                except OSError:
                    pass
                return "ERROR: 下载不完整（{}/{} bytes）".format(written, total)
    except Exception as e:
        # 残留半文件也清掉
        try:
            if os.path.exists(dest_path):
                os.remove(dest_path)
        except OSError:
            pass
        return "ERROR: 下载失败：{}".format(e)

    return dest_path
