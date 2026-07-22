# Vendored JavaScript

## `jsonc_parser.js`

Single-file ESM bundle of [microsoft/jsonc-parser](https://github.com/microsoft/node-jsonc-parser) for classpath consumption from ClojureScript:

```clojure
(:require ["/vscode_mcp/vendor/jsonc_parser" :as jsonc])
```

Pinned version is recorded in `jsonc_parser.VERSION`.

Refresh / upgrade:

```sh
bb vendor-jsonc-parser
bb vendor-jsonc-parser --version 3.3.1
```

Requires `npm` / `npx` (esbuild is invoked ephemerally; not a project dependency).
