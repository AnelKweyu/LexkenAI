#!/usr/bin/env python3
"""Flatten a Django project's URLconf into one route pattern per line.

Usage:
    python -I tools/route-catalog.py <django project root> <root urls module> <output file> [--ws]

Examples (the two read-only backend checkouts, both kept on prod_staging):
    python -I tools/route-catalog.py \
        "C:/Users/nicol/Downloads/lexken-ai-backend/lexken-lawris/lawris-app/lawris_django" \
        lawris_django.urls app/src/test/resources/routes/lawris.txt --ws
    python -I tools/route-catalog.py \
        "C:/Users/nicol/Downloads/lexken-vf_sandychix/case-mx-app-service" \
        config.urls app/src/test/resources/routes/identity.txt

Output lines:
    users/profile/nav-info/                 a `path()` route, Django converters kept (<uuid:id>)
    re:^api/list_folder_contents/(?P<p>.+)/$  a `re_path()` route, the regex kept verbatim
    ws:ws/ai/lawris-ai/$                    a Channels route from */routing.py (with --ws)

DRF routers are expanded to `<prefix>/`, `<prefix>/<pk>/` and one wildcard segment for
`@action` routes, since those live on the viewset, not in urls.py. Third-party includes
(rest_framework, oauth2_provider, prometheus, debug toolbar) are skipped: the app never calls them.

The urls files are read as data (parsed with `ast`), never imported or executed.
"""
import ast
import sys
from pathlib import Path

EXTERNAL_PREFIXES = ("rest_framework", "oauth2_provider", "django_prometheus", "debug_toolbar", "django.")


def module_file(root: Path, dotted: str) -> Path | None:
    candidate = root.joinpath(*dotted.split("."))
    if candidate.with_suffix(".py").exists():
        return candidate.with_suffix(".py")
    if (candidate / "__init__.py").exists():
        return candidate / "__init__.py"
    return None


def const_str(node) -> str | None:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def include_target(node):
    """Return ('module', 'a.b.urls') | ('router', 'router') | ('name', 'var') | None for an include() call."""
    if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "include"):
        return None
    if not node.args:
        return None
    arg = node.args[0]
    if isinstance(arg, ast.Tuple) and arg.elts:
        arg = arg.elts[0]
    text = const_str(arg)
    if text is not None:
        return ("module", text)
    if isinstance(arg, ast.Attribute) and arg.attr == "urls" and isinstance(arg.value, ast.Name):
        return ("router", arg.value.id)
    if isinstance(arg, ast.Name):
        return ("name", arg.id)
    return None


class Catalog:
    def __init__(self, root: Path, include_ws: bool):
        self.root = root
        self.include_ws = include_ws
        self.routes: set[str] = set()
        self.visited: set[tuple[str, str]] = set()
        self.missing: list[str] = []

    # -- urls.py -------------------------------------------------------------
    def walk_module(self, dotted: str, prefix: str) -> None:
        if dotted.startswith(EXTERNAL_PREFIXES):
            return
        key = (dotted, prefix)
        if key in self.visited:
            return
        self.visited.add(key)
        file = module_file(self.root, dotted)
        if file is None:
            self.missing.append(dotted)
            return
        tree = ast.parse(file.read_text(encoding="utf-8"), filename=str(file))
        routers = self.router_prefixes(tree)
        # `from lawris_django.urls import urlpatterns as lawris_urlpatterns` and
        # `from .crypt_demo import public_encryption_demo_patterns`: pattern lists composed by
        # import rather than include() — walk their modules under the same prefix.
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and any("patterns" in alias.name for alias in node.names):
                package = dotted.rsplit(".", node.level)[0] if node.level else ""
                target = f"{package}.{node.module}" if package else node.module
                self.walk_module(target, prefix)
        for call in ast.walk(tree):
            if not (isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id in ("path", "re_path")):
                continue
            if len(call.args) < 2:
                continue
            pattern = const_str(call.args[0])
            if pattern is None:
                continue  # settings.ADMIN_URL and friends: not a literal
            is_regex = call.func.id == "re_path"
            target = include_target(call.args[1])
            if target is None:
                self.add(prefix, pattern, is_regex)
            elif target[0] == "module":
                self.walk_module(target[1], self.join(prefix, pattern))
            elif target[0] == "router":
                base = self.join(prefix, pattern)
                for router_prefix in routers.get(target[1], []):
                    self.add(base, f"{router_prefix}/", False)
                    self.add(base, f"{router_prefix}/<pk>/", False)
                    self.add(base, f"{router_prefix}/<pk>/<action>/", False)
                    self.add(base, f"{router_prefix}/<action>/", False)
            else:  # a patterns list defined in another module (e.g. telemetry_urlpatterns): already walked via its module
                pass

    @staticmethod
    def router_prefixes(tree) -> dict[str, list[str]]:
        found: dict[str, list[str]] = {}
        for call in ast.walk(tree):
            if (
                isinstance(call, ast.Call)
                and isinstance(call.func, ast.Attribute)
                and call.func.attr == "register"
                and isinstance(call.func.value, ast.Name)
                and call.args
            ):
                literal = const_str(call.args[0])
                if literal is not None:
                    found.setdefault(call.func.value.id, []).append(literal.strip("^$"))
        return found

    @staticmethod
    def join(prefix: str, pattern: str) -> str:
        return prefix + pattern

    def add(self, prefix: str, pattern: str, is_regex: bool) -> None:
        if is_regex:
            body = pattern.lstrip("^")
            self.routes.add(f"re:^{prefix}{body}" if body.endswith("$") else f"re:^{prefix}{body}$")
        else:
            self.routes.add(prefix + pattern)

    # -- routing.py (Channels) -----------------------------------------------
    def walk_routing(self) -> None:
        for file in sorted(self.root.rglob("routing.py")):
            tree = ast.parse(file.read_text(encoding="utf-8"), filename=str(file))
            for call in ast.walk(tree):
                if isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id in ("path", "re_path") and call.args:
                    pattern = const_str(call.args[0])
                    if pattern is None:
                        continue
                    if call.func.id == "re_path":
                        body = pattern.lstrip("^")
                        self.routes.add(f"ws:^{body}" if body.endswith("$") else f"ws:^{body}$")
                    else:
                        self.routes.add(f"ws:{pattern}")


def main(argv: list[str]) -> int:
    if len(argv) < 4:
        print(__doc__)
        return 2
    root, dotted, out = Path(argv[1]), argv[2], Path(argv[3])
    include_ws = "--ws" in argv
    catalog = Catalog(root, include_ws)
    catalog.walk_module(dotted, "")
    if include_ws:
        catalog.walk_routing()
    lines = sorted(catalog.routes)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{out}: {len(lines)} routes from {dotted} under {root}")
    if catalog.missing:
        print("unresolved includes (skipped):", ", ".join(sorted(set(catalog.missing))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
