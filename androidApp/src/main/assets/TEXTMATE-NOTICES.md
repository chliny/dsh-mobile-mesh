# Third-Party Notices

KotlinTextMate includes and is derived from third-party material. The original
copyright notices and permission notices are retained below, as required by the
respective licenses. KotlinTextMate itself is licensed under the [MIT License](LICENSE).

## Ported source code

### vscode-textmate

KotlinTextMate is a Kotlin port of [vscode-textmate](https://github.com/microsoft/vscode-textmate)
(baseline version 9.3.2 — see [docs/UPSTREAM.md](docs/UPSTREAM.md)). Substantial portions of
this project's source are direct translations of the original TypeScript.

> Copyright (c) Microsoft Corporation
>
> Licensed under the MIT License.

## Bundled grammars (`shared-assets/grammars/`)

These TextMate grammar files are redistributed unmodified except for JSONC-to-JSON
normalization. All are MIT-licensed.

| File | Source | Copyright |
|---|---|---|
| `JSON.tmLanguage.json` | [microsoft/vscode-JSON.tmLanguage](https://github.com/microsoft/vscode-JSON.tmLanguage) | © Microsoft Corporation |
| `JavaScript.tmLanguage.json` | [microsoft/vscode](https://github.com/microsoft/vscode) (TypeScript-TmLanguage) | © Microsoft Corporation |
| `markdown.tmLanguage.json` | [microsoft/vscode-markdown-tm-grammar](https://github.com/microsoft/vscode-markdown-tm-grammar) | © Microsoft Corporation |
| `kotlin.tmLanguage.json` | [mathiasfrohlich/vscode-kotlin](https://github.com/mathiasfrohlich/vscode-kotlin) | © Mathias Fröhlich |

## Additional language grammars bundled in this Android app

The following MIT-licensed grammar JSON files are redistributed from `tm-grammars` 1.32.17,
except for YAML from Red Hat's `vscode-yaml` and TOML from `tamasfe/taplo`. The package README records
upstream sources and licenses. The VS Code grammars are copyright Microsoft Corporation; the
Swift grammar is copyright John Bandelet. The R grammar comes from Positron, the Scala grammar
from scala/vscode-scala-syntax, and the JSON5 grammar from mrmlnc/vscode-json5.
See `VSCODE-GRAMMARS-LICENSE.txt` in this app's assets for the MIT license notices and links.

| Asset | Language | Upstream source |
|---|---|---|
| `textmate-c.tmLanguage.json` | C | [Microsoft VS Code C grammar](https://github.com/microsoft/vscode/blob/main/extensions/cpp/syntaxes/c.tmLanguage.json) |
| `textmate-cpp.tmLanguage.json` | C++ | [Microsoft VS Code C++ grammar](https://github.com/microsoft/vscode/blob/main/extensions/cpp/syntaxes/cpp.tmLanguage.json) |
| `textmate-csharp.tmLanguage.json` | C# | [Microsoft VS Code C# grammar](https://github.com/microsoft/vscode/blob/main/extensions/csharp/syntaxes/csharp.tmLanguage.json) |
| `textmate-css.tmLanguage.json` | CSS | [Microsoft VS Code CSS grammar](https://github.com/microsoft/vscode/blob/main/extensions/css/syntaxes/css.tmLanguage.json) |
| `textmate-dart.tmLanguage.json` | Dart | [Microsoft VS Code Dart grammar](https://github.com/microsoft/vscode/blob/main/extensions/dart/syntaxes/dart.tmLanguage.json) |
| `textmate-groovy.tmLanguage.json` | Groovy | [Microsoft VS Code Groovy grammar](https://github.com/microsoft/vscode/blob/main/extensions/groovy/syntaxes/groovy.tmLanguage.json) |
| `textmate-lua.tmLanguage.json` | Lua | [Microsoft VS Code Lua grammar](https://github.com/microsoft/vscode/blob/main/extensions/lua/syntaxes/lua.tmLanguage.json) |
| `textmate-objective-c.tmLanguage.json` | Objective-C | [Microsoft VS Code Objective-C grammar](https://github.com/microsoft/vscode/blob/main/extensions/objective-c/syntaxes/objective-c.tmLanguage.json) |
| `textmate-perl.tmLanguage.json` | Perl | [Microsoft VS Code Perl grammar](https://github.com/microsoft/vscode/blob/main/extensions/perl/syntaxes/perl.tmLanguage.json) |
| `textmate-powershell.tmLanguage.json` | PowerShell | [Microsoft VS Code PowerShell grammar](https://github.com/microsoft/vscode/blob/main/extensions/powershell/syntaxes/powershell.tmLanguage.json) |
| `textmate-r.tmLanguage.json` | R | [Positron R grammar](https://github.com/posit-dev/positron/tree/main/extensions/positron-r/syntaxes) |
| `textmate-scala.tmLanguage.json` | Scala | [Scala VS Code grammar](https://github.com/scala/vscode-scala-syntax) |
| `textmate-go.tmLanguage.json` | Go | [Microsoft VS Code Go grammar](https://github.com/microsoft/vscode/blob/main/extensions/go/syntaxes/go.tmLanguage.json) |
| `textmate-html.tmLanguage.json` | HTML | [Microsoft VS Code HTML grammar](https://github.com/microsoft/vscode/blob/main/extensions/html/syntaxes/html.tmLanguage.json) |
| `textmate-java.tmLanguage.json` | Java | [Microsoft VS Code Java grammar](https://github.com/microsoft/vscode/blob/main/extensions/java/syntaxes/java.tmLanguage.json) |
| `textmate-php.tmLanguage.json` | PHP | [Microsoft VS Code PHP grammar](https://github.com/microsoft/vscode/blob/main/extensions/php/syntaxes/php.tmLanguage.json) |
| `textmate-python.tmLanguage.json` | Python | [Microsoft VS Code Python grammar](https://github.com/microsoft/vscode/blob/main/extensions/python/syntaxes/MagicPython.tmLanguage.json) |
| `textmate-ruby.tmLanguage.json` | Ruby | [Microsoft VS Code Ruby grammar](https://github.com/microsoft/vscode/blob/main/extensions/ruby/syntaxes/ruby.tmLanguage.json) |
| `textmate-rust.tmLanguage.json` | Rust | [Microsoft VS Code Rust grammar](https://github.com/microsoft/vscode/blob/main/extensions/rust/syntaxes/rust.tmLanguage.json) |
| `textmate-shellscript.tmLanguage.json` | Shell | [Microsoft VS Code Shell grammar](https://github.com/microsoft/vscode/blob/main/extensions/shellscript/syntaxes/shell-unix-bash.tmLanguage.json) |
| `textmate-sql.tmLanguage.json` | SQL | [Microsoft VS Code SQL grammar](https://github.com/microsoft/vscode/blob/main/extensions/sql/syntaxes/sql.tmLanguage.json) |
| `textmate-swift.tmLanguage.json` | Swift | [John Bandelet Swift grammar](https://github.com/jtbandes/swift-tmlanguage) |
| `textmate-tsx.tmLanguage.json` | TSX | [Microsoft VS Code TypeScript React grammar](https://github.com/microsoft/vscode/blob/main/extensions/typescript-basics/syntaxes/TypeScriptReact.tmLanguage.json) |
| `textmate-typescript.tmLanguage.json` | TypeScript | [Microsoft VS Code TypeScript grammar](https://github.com/microsoft/vscode/blob/main/extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json) |
| `textmate-xml.tmLanguage.json` | XML | [Microsoft VS Code XML grammar](https://github.com/microsoft/vscode/blob/main/extensions/xml/syntaxes/xml.tmLanguage.json) |
| `textmate-docker.tmLanguage.json` | Dockerfile | [Microsoft VS Code Docker grammar](https://github.com/microsoft/vscode/blob/main/extensions/docker/syntaxes/docker.tmLanguage.json) |
| `textmate-make.tmLanguage.json` | Makefile | [Microsoft VS Code Make grammar](https://github.com/microsoft/vscode/blob/main/extensions/make/syntaxes/make.tmLanguage.json) |
| `textmate-scss.tmLanguage.json` | SCSS | [Microsoft VS Code SCSS grammar](https://github.com/microsoft/vscode/blob/main/extensions/scss/syntaxes/scss.tmLanguage.json) |
| `textmate-less.tmLanguage.json` | Less | [Microsoft VS Code Less grammar](https://github.com/microsoft/vscode/blob/main/extensions/less/syntaxes/less.tmLanguage.json) |
| `textmate-ini.tmLanguage.json` | INI | [Microsoft VS Code INI grammar](https://github.com/microsoft/vscode/blob/main/extensions/ini/syntaxes/ini.tmLanguage.json) |
| `textmate-jsonc.tmLanguage.json` | JSON with Comments | [Microsoft VS Code JSONC grammar](https://github.com/microsoft/vscode/blob/main/extensions/json/syntaxes/JSONC.tmLanguage.json) |
| `textmate-json5.tmLanguage.json` | JSON5 | [mrmlnc/vscode-json5](https://github.com/mrmlnc/vscode-json5/blob/master/syntaxes/json5.json), MIT |
| `textmate-yaml.tmLanguage.json` | YAML | [Red Hat vscode-yaml](https://github.com/redhat-developer/vscode-yaml/blob/main/syntaxes/yaml.tmLanguage.json), MIT, © Red Hat Inc. and others |
| `textmate-toml.tmLanguage.json` | TOML | [Taplo VS Code grammar](https://github.com/tamasfe/taplo/blob/master/editors/vscode/toml.tmLanguage.json), MIT, © Taplo contributors |


### Broader language coverage

These additional grammars extend code-block and file-preview highlighting beyond the mainstream
web/JVM set. All are redistributed unmodified from tm-grammars 1.32.17. MIT-licensed files are
covered by the MIT text in `VSCODE-GRAMMARS-LICENSE.txt`; the Apache-2.0, BSD-3-Clause, and MPL-2.0
grammars are covered by `THIRD-PARTY-GRAMMAR-LICENSES.txt`.

| Asset | Language | Upstream source | License |
|---|---|---|---|
| `textmate-elixir.tmLanguage.json` | Elixir | [elixir-editors/elixir-tmbundle](https://github.com/elixir-editors/elixir-tmbundle) | Apache-2.0 |
| `textmate-erlang.tmLanguage.json` | Erlang | [erlang-ls/grammar](https://github.com/erlang-ls/grammar) | Apache-2.0 |
| `textmate-haskell.tmLanguage.json` | Haskell | [octref/language-haskell](https://github.com/octref/language-haskell) | BSD-3-Clause |
| `textmate-clojure.tmLanguage.json` | Clojure | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/clojure/syntaxes/clojure.tmLanguage.json) | MIT |
| `textmate-ocaml.tmLanguage.json` | OCaml | [reasonml-editor/vscode-reasonml](https://github.com/reasonml-editor/vscode-reasonml) | Apache-2.0 |
| `textmate-fsharp.tmLanguage.json` | F# | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/fsharp/syntaxes/fsharp.tmLanguage.json) | MIT |
| `textmate-zig.tmLanguage.json` | Zig | [ziglang/vscode-zig](https://github.com/ziglang/vscode-zig) | MIT |
| `textmate-hcl.tmLanguage.json` | HCL | [hashicorp/syntax](https://github.com/hashicorp/syntax) | MPL-2.0 |
| `textmate-terraform.tmLanguage.json` | Terraform | [hashicorp/syntax](https://github.com/hashicorp/syntax) | MPL-2.0 |
| `textmate-graphql.tmLanguage.json` | GraphQL | [prisma-labs/vscode-graphql](https://github.com/prisma-labs/vscode-graphql) | MIT |
| `textmate-proto.tmLanguage.json` | Protocol Buffers | [zxh0/vscode-proto3](https://github.com/zxh0/vscode-proto3) | MIT |
| `textmate-cmake.tmLanguage.json` | CMake | [twxs/vs.language.cmake](https://github.com/twxs/vs.language.cmake) | MIT |
| `textmate-nix.tmLanguage.json` | Nix | [nix-community/vscode-nix-ide](https://github.com/nix-community/vscode-nix-ide) | MIT |
| `textmate-julia.tmLanguage.json` | Julia | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/julia/syntaxes/julia.tmLanguage.json) | MIT |
| `textmate-asm.tmLanguage.json` | Assembly | [13xforever/x86_64-assembly-vscode](https://github.com/13xforever/x86_64-assembly-vscode) | MIT |
| `textmate-bat.tmLanguage.json` | Batch | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/bat/syntaxes/batchfile.tmLanguage.json) | MIT |
| `textmate-vb.tmLanguage.json` | VB.NET | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/vb/syntaxes/asp-vb-net.tmLanguage.json) | MIT |
| `textmate-pascal.tmLanguage.json` | Pascal | [alefragnani/vscode-language-pascal](https://github.com/alefragnani/vscode-language-pascal) | MIT |
| `textmate-solidity.tmLanguage.json` | Solidity | [juanfranblanco/vscode-solidity](https://github.com/juanfranblanco/vscode-solidity) | MIT |
| `textmate-verilog.tmLanguage.json` | Verilog | [mshr-h/vscode-verilog-hdl-support](https://github.com/mshr-h/vscode-verilog-hdl-support) | MIT |
| `textmate-vhdl.tmLanguage.json` | VHDL | [jonasjj/awesome-vhdl](https://github.com/jonasjj/awesome-vhdl) | MIT |
| `textmate-latex.tmLanguage.json` | LaTeX | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/latex/syntaxes/LaTeX.tmLanguage.json) | MIT |
| `textmate-markdown.tmLanguage.json` | Markdown | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/markdown-basics/syntaxes/markdown.tmLanguage.json) | MIT |
| `textmate-mdx.tmLanguage.json` | MDX | [wooorm/markdown-tm-language](https://github.com/wooorm/markdown-tm-language) | MIT |
| `textmate-vue.tmLanguage.json` | Vue | [vuejs/language-tools](https://github.com/vuejs/language-tools) | MIT |
| `textmate-svelte.tmLanguage.json` | Svelte | [sveltejs/language-tools](https://github.com/sveltejs/language-tools) | MIT |
| `textmate-astro.tmLanguage.json` | Astro | [withastro/language-tools](https://github.com/withastro/language-tools) | MIT |
| `textmate-awk.tmLanguage.json` | AWK | [luggage66/vscode-awk](https://github.com/luggage66/vscode-awk) | MIT |
| `textmate-diff.tmLanguage.json` | Diff | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/diff/syntaxes/diff.tmLanguage.json) | MIT |
| `textmate-dotenv.tmLanguage.json` | dotenv | [dotenv-org/dotenv-vscode](https://github.com/dotenv-org/dotenv-vscode) | MIT |
| `textmate-http.tmLanguage.json` | HTTP | [Huachao/vscode-restclient](https://github.com/Huachao/vscode-restclient) | MIT |
| `textmate-jsonl.tmLanguage.json` | JSON Lines | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/json/syntaxes/JSONL.tmLanguage.json) | MIT |
| `textmate-coffee.tmLanguage.json` | CoffeeScript | [microsoft/vscode](https://github.com/microsoft/vscode/blob/main/extensions/coffeescript/syntaxes/coffeescript.tmLanguage.json) | MIT |

The grammar JSON files are adapted/redistributed by [tm-grammars 1.32.17](https://cdn.jsdelivr.net/npm/tm-grammars@1.32.17/README.md).

## Bundled themes (`shared-assets/themes/`)

VS Code default color themes, redistributed with JSONC trailing commas stripped.
Sourced from [microsoft/vscode](https://github.com/microsoft/vscode) (`extensions/theme-defaults/themes/`),
MIT-licensed, © Microsoft Corporation.

- `textmate-light.json` — `light_vs.json` + `light_plus.json` (the Light+ rules flattened into one file)
- `textmate-dark.json` — `dark_vs.json` + `dark_plus.json` (the Dark+ rules flattened into one file)

The `*_plus` layer is what colors function names, types, variables, parameters, and object keys;
without it a TextMate-only renderer shows little more than keywords, strings, comments, and numbers.

## Benchmark corpus (`shared-assets/benchmark/`)

- `jquery.js.txt` — [jQuery](https://jquery.com/), MIT License, © OpenJS Foundation and jQuery contributors.

---

The MIT License text for all of the above is identical to the one in [LICENSE](LICENSE);
only the copyright holders differ.
