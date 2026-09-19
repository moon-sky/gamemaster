"""网络搜索工具：用 requests 抓 Bing 搜索结果页，bs4 解析前 8 条。

ChaquopyToolRunner 会通过 chaquopy.Python.getInstance().getModule("tools.web_search")
.callAttr("search", query=...) 调用入口函数 search(query, max_results=8)。

输出格式：JSON 字符串，方便 Kotlin 侧解析为 ToolResult.output。
"""
import json

import requests
from bs4 import BeautifulSoup


BING_URL = "https://cn.bing.com/search"

# 桌面端 UA + 完整请求头，避免被识别为爬虫或返回空内容
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip, deflate",
    "Connection": "keep-alive",
}


def search(query: str, max_results: int = 8) -> str:
    """搜索关键词，返回 JSON 字符串：[{"title": ..., "url": ..., "snippet": ...}, ...]"""
    if not query or not query.strip():
        return json.dumps({"error": "query 不能为空"}, ensure_ascii=False)

    try:
        resp = requests.get(
            BING_URL,
            params={"q": query, "count": max_results},
            headers=HEADERS,
            timeout=15,
        )
        resp.raise_for_status()
    except Exception as e:
        return json.dumps({"error": f"请求失败：{e}"}, ensure_ascii=False)

    soup = BeautifulSoup(resp.text, "html.parser")
    results = []
    # Bing 搜索结果在 <li class="b_algo"> 中
    for item in soup.select("li.b_algo"):
        title_el = item.select_one("h2 a")
        if not title_el:
            continue
        title = title_el.get_text(strip=True)
        url = title_el.get("href", "")
        # 摘要在 .b_caption 下的 <p> 里
        snippet_el = item.select_one(".b_caption p")
        snippet = snippet_el.get_text(strip=True) if snippet_el else ""
        if title:
            results.append({"title": title, "url": url, "snippet": snippet})
        if len(results) >= max_results:
            break

    if not results:
        return json.dumps({"error": "未找到结果，可能被限流或关键词不命中"}, ensure_ascii=False)
    return json.dumps(results, ensure_ascii=False)
