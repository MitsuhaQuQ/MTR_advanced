# scripts/extract.mjs

从 `editor.js` 中按原样切出旧编辑器的顶层声明（IIFE 内缩进一个制表符的 `function` / `const` / `let`）。它懂得足够的 JavaScript 来找到声明在哪里结束：字符串、带 `${…}` 的模板字符串、正则表达式字面量和注释。被 `scripts/gen-legacy.mjs`（`declarations`）使用；文件注释说对照测试也用它，但 `test/old.ts` 有自己的一份 `extract`/`endOf` 实现，未找到导入本文件之处（未确定）。纯函数，不读写文件。

## 内部
- `REGEX_BEFORE`：出现在其后的 `/` 视为正则开头的字符集合。
- `skipString(src, i, quote)`：从引号处跳过字符串（处理转义），返回结束后的位置。
- `skipTemplate(src, i)`：从反引号之后跳过模板字符串，`${…}` 内按代码扫描（`skipCode`）；未闭合抛错。
- `skipCode(src, i, close)`：跳过代码直到深度 0 的闭括号 `close`，返回其后位置；跳过注释、字符串、模板、正则。不平衡时抛错。
- `isRegexStart(src, i, last)`：根据前一个非空白字符（或前面的 `return`/`typeof`/`case`）判断 `/` 是否开始正则。
- `skipRegex(src, i)`：跳过正则字面量（含字符类 `[...]` 和标志）。

## 导出
- `endOf(src, start, isFunction)`：声明结束位置（不含）：函数为深度回到 0 的函数体 `}`，`const`/`let` 为深度 0 的 `;`。找不到抛错。调用者：本文件 `declarations`。
- `declaredNames(text)`：一个 `const`/`let` 声明声明的名字（`const a = 1, b = 2;` → `["a", "b"]`），跳过初始值中的括号、字符串等。调用者：本文件 `declarations`。
- `declarations(src)`：按顺序返回所有顶层声明 `{ kind: "function" | "const" | "let", names, text, start, end }`（`start` 指向关键字，去掉前导制表符）；非声明语句（事件监听、启动代码）不包含。匹配行首为一个制表符加 `function name(`（可带 `async`）或 `const `/`let `。调用者：`scripts/gen-legacy.mjs:86`、`scripts/gen-legacy.mjs:87`。
- `extract(src, name)`：按名字取一个顶层函数或常量的源码；找不到抛错。`editor-web` 中未找到调用者（`test/old.ts` 有同名的自身实现）。
