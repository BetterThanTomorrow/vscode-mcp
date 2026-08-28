(ns vscode-mcp.registry-listing-stubs
  "First-fail README and AGENTS.md, inlined from assets/registry-content/fallback."
  (:require
   [shadow.resource :as rc]))

(def readme
  (rc/inline "registry-content/fallback/README.md"))

(def agents
  (rc/inline "registry-content/fallback/AGENTS.md"))
