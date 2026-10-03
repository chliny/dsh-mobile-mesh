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

The following MIT-licensed grammar JSON files are redistributed from `tm-grammars` 1.32.17.
The package README records the upstream source and license for each grammar. The VS Code
sources are copyright Microsoft Corporation; the Swift grammar is copyright John Bandelet.
The R grammar comes from Positron and the Scala grammar from scala/vscode-scala-syntax.
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

The grammar JSON files are adapted/redistributed by [tm-grammars 1.32.17](https://cdn.jsdelivr.net/npm/tm-grammars@1.32.17/README.md).

## Bundled themes (`shared-assets/themes/`)

VS Code default color themes, redistributed with JSONC trailing commas stripped.
Sourced from [microsoft/vscode](https://github.com/microsoft/vscode) (`extensions/theme-defaults/themes/`),
MIT-licensed, © Microsoft Corporation.

- `dark_vs.json`, `dark_plus.json`, `light_vs.json`, `light_plus.json`

## Benchmark corpus (`shared-assets/benchmark/`)

- `jquery.js.txt` — [jQuery](https://jquery.com/), MIT License, © OpenJS Foundation and jQuery contributors.

---

The MIT License text for all of the above is identical to the one in [LICENSE](LICENSE);
only the copyright holders differ.
