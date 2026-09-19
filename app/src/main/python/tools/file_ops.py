"""设备文件操作工具：read / write / list。

入口函数：operate(op, path, content="") -> str
- op: "read" | "write" | "list"
- path: 目标路径
- content: 仅 write 时使用，要写入的内容
输出：成功返回结果字符串；失败返回 "ERROR: <原因>"。

注意：Chaquopy 跑在 app uid，可读写范围仅限 app 私有目录与外部存储公共目录。
要读 /data/ 等系统路径，需要 Kotlin 侧用 BackendSelector.bestForShell()?.execOut("cat ...")
转 shell 执行。
"""
import os


def operate(op: str, path: str, content: str = "") -> str:
    if not op or not op.strip():
        return "ERROR: op 不能为空（read/write/list）"
    op = op.strip().lower()
    if not path:
        return "ERROR: path 不能为空"

    try:
        if op == "read":
            if not os.path.exists(path):
                return "ERROR: 文件不存在：{}".format(path)
            if not os.path.isfile(path):
                return "ERROR: 路径不是文件：{}".format(path)
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                text = f.read(8192)  # 最多 8KB，避免一次吃爆模型上下文
            if len(text) >= 8192:
                text += "\n...（已截断到 8KB）"
            return text

        if op == "write":
            if not content:
                return "ERROR: write 操作需要 content 参数"
            # 父目录不存在时尝试创建
            parent = os.path.dirname(path)
            if parent and not os.path.isdir(parent):
                os.makedirs(parent, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            return "OK: 写入 {} 字节到 {}".format(len(content), path)

        if op == "list":
            if not os.path.exists(path):
                return "ERROR: 目录不存在：{}".format(path)
            if not os.path.isdir(path):
                return "ERROR: 路径不是目录：{}".format(path)
            entries = os.listdir(path)
            # 区分目录与文件
            lines = []
            for name in sorted(entries):
                full = os.path.join(path, name)
                kind = "[D]" if os.path.isdir(full) else "[F]"
                size = ""
                if os.path.isfile(full):
                    try:
                        size = " {}B".format(os.path.getsize(full))
                    except OSError:
                        pass
                lines.append("{} {}{}".format(kind, name, size))
            if not lines:
                return "（目录为空）"
            return "\n".join(lines)

        return "ERROR: 未知 op={}，应为 read/write/list".format(op)
    except PermissionError as e:
        return "ERROR: 权限不足（{}）——要读 /data 等系统路径需通过 shell 后端".format(e)
    except OSError as e:
        return "ERROR: 操作失败：{}".format(e)
