"""网页正文提取工具：抓 URL，bs4 清洗，返回纯文本。

入口函数：read(url, max_chars=2000) -> str
输出：正文文本，截断到 max_chars。失败时返回 "ERROR: <原因>"。
"""
import requests
from bs4 import BeautifulSoup


HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
}


def read(url: str, max_chars: int = 2000) -> str:
    if not url or not url.strip():
        return "ERROR: url 不能为空"
    if not (url.startswith("http://") or url.startswith("https://")):
        return "ERROR: url 必须以 http:// 或 https:// 开头"

    try:
        resp = requests.get(url, headers=HEADERS, timeout=15)
        resp.raise_for_status()
    except Exception as e:
        return f"ERROR: 请求失败：{e}"

    soup = BeautifulSoup(resp.text, "html.parser")
    # 先把 script / style / nav / footer 干掉
    for tag in soup(["script", "style", "nav", "footer", "header", "aside"]):
        tag.decompose()

    # 优先级：<article> > <main> > 最长 <div>
    text = ""
    for selector in ["article", "main", "[role=main]"]:
        node = soup.select_one(selector)
        if node:
            text = node.get_text(separator="\n", strip=True)
            if len(text) > 200:
                break

    if not text or len(text) < 200:
        # 退化为找最长 div
        longest = ""
        for div in soup.find_all("div"):
            t = div.get_text(separator="\n", strip=True)
            if len(t) > len(longest):
                longest = t
        text = longest

    # 折叠多余空行
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    text = "\n".join(lines)

    if not text:
        return "ERROR: 未提取到正文（可能是 JS 渲染页面或登录墙）"

    if len(text) > max_chars:
        text = text[:max_chars] + "\n...（已截断）"
    return text
