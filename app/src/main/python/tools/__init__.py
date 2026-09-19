"""gamemaster 内置 Python 工具包。

每个工具模块对外暴露一个返回字符串的入口函数，由 ChaquopyToolRunner 调用。
约定：异常向上抛，Kotlin 侧捕获后封装成 ToolResult.error。
"""


def hello(name: str = "world") -> str:
    """Chaquopy 接入冒烟测试用。"""
    return f"hello, {name}!"
