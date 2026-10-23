# Design principles (the BioEnv Caller framework)

[中文](DESIGN.md) ｜ **English**

This file governs every implementation decision. Any code that conflicts with it is a defect.

---

## P1. No hard-coded tool names

**No concrete tool name may appear in the code.**

Test: `grep` the sources. Outside comments, documentation and test fixtures you should not find
`mafft`, `trimal`, `iqtree`, `hmmer`, `samtools` or any other concrete command name.

What "don't do this" looks like:

- ❌ maintaining a "list of supported software"
- ❌ `if (tool.command.equals("mafft")) { ... }`
- ❌ hand-writing a parameter form for one tool

Reason: the moment the framework needs to know tool names in advance, it only generalises over
"the few that made it onto the list" and not over the other few thousand tools in the world.
That is not a general framework, it is a curated collection.

---

## P2. One source of tools: conda environments

Only the virtual environments that conda created inside WSL are scanned.

Not scanned: `/usr/bin`, `/usr/local/bin`, Windows mount points on `PATH` (`/mnt/c/...`).

Reason: people use conda precisely for isolation and tidiness. Going around conda and scanning
the system `PATH` drags in Windows-side `.exe` files, system utilities and script fragments, and
destroys that tidiness.

This means `EnvScanner` only understands conda, and **that is deliberate, not a missing
capability**.

---

## P3. Parameter information comes only from the tool itself

The only source of a parameter schema is the tool's own help output
(`-h` / `--help` / `-help` / `help` / bare invocation, tried in turn, keeping whichever one
yields the most parsed options).

Not done: a built-in parameter table, a built-in default-value table, a built-in mapping of
parameter names.

Reason: a built-in table necessarily lags behind tool upgrades and is never complete. The tool's
own help is always in sync with its version.

---

## P4. Usable without being known in advance

A tool that has never been seen before must become usable automatically as long as it is in a
conda environment:

1. it is discovered by `EnvScanner`
2. it is recognised by the help probe
3. its parameters are parsed into a UI
4. it can be run directly

Nowhere in that path does anyone write code for it.

---

## P5. Multi-level subcommands are treated the same way

Multi-level commands such as `samtools stats` and `bcftools view` take the same code path as
single-level commands such as `mafft`; the only difference is the recursion depth.

No special branch may be written for multi-level tools.

---

## P6. A parse failure must degrade, never disable

When help cannot be parsed the tool is still usable: it degrades to "plain command-line mode",
where the user writes the arguments by hand and runs it.

Reason: being unusable is unacceptable; a rough UI is acceptable.

---

## P7. Inferences may be overruled

The control type inferred from help text (for example `<inputfile>` → file chooser) is only a
default. The user must be able to change it.

Reason: text inference can never be 100% accurate, and treating a guess as a verdict leaves the
user unable to express what they actually mean.

Inferences must carry a **confidence level**, and the UI must show it honestly:

- high confidence — usable as is
- low confidence — marked, so the user knows to check
- no signal — degrades to the safest text field

And **empty beats wrong**: only strong evidence is accepted as a default. A "default" grabbed out
of thin air silently enters the command line, which is far more dangerous than an empty field —
the user fills an empty field, but runs a wrong default without noticing.

---

## P9. Tools are grouped by environment

The tool list is grouped by **the conda environment that provides each tool**, with environments
as parent nodes and commands as child nodes.

Reason: that is the structure the discovery result naturally has. Users organise and remember
their tools by environment; merging commands from different environments into one flat list
leaves no way to tell where a command came from, or whether it collides with the copy they
expected.

The grouping comes out of discovery, not from any preset knowledge, so it does not violate P1.

---

## P10. The layered order of type detection

A parameter type can only be inferred from help text, so the signals are tried **from most to
least reliable**, and the first success wins. A lower-reliability signal may never overwrite a
higher-reliability one.

| Order | Signal | Note |
|---|---|---|
| 1 | placeholder shape | `*_file`, `*_value`, `int_value`, `#`, `N`, `<...>` |
| 2 | leading noun of the description | `Path to`, `Number of`, `Output directory` |
| 3 | enumeration candidates | `{a,b}`, `a\|b`, `one of:`, `can be:` |
| 4 | description keywords | whole-sentence search, least reliable |

Enumeration detection is independent of the first three: as soon as a candidate set appears, the
type is choice.

## P12. The command contains only what the user expressed

**Only the parameters the user actually typed or picked appear on the command line.** Anything
untouched never appears.

What "expressed" means:

| Case | Counts as expressed? |
|---|---|
| the user typed something into a text field | yes |
| the user ticked a switch | yes |
| the user **changed a dropdown away from its default** | yes |
| the text field was left empty | no |
| the switch was left unticked | no |
| the dropdown sits on its default and the user never touched it | **no** |

Reason: the tool knows its own defaults. Repeating them is redundant; repeating them wrongly is
a disaster — passing `[false]` as the value of a boolean switch makes it treat `false` as a file
name or a non-zero value.

Corollary: **a default read from help is shown in the UI as a pale hint only; it never enters
the command line.**

---

## P13. The command must stay short and readable

The generated command line should look like something a person wrote by hand, not like a dump of
UI state.

- **Quote only when needed**: a value with no whitespace and no shell metacharacters is emitted
  bare (`-M msa`); only values with whitespace or metacharacters get quotes
  (`-o '/mnt/c/My Data/out.fa'`). Wrapping every value in quotes (`'0.5'`, `'pep'`) is noise.
- **No meaningless redundancy**: a boolean switch emits just the flag when ticked, never a value.
- **Integers are not written as floats**: `-t 12`, not `-t 12.0`.
- **In the preview a long path is shown by its last segment only**: a file chooser hands over a
  full Windows path, which translates to something long like
  `/mnt/c/Users/.../Results_Sep07_2`; two of those and the command is drowned. In the preview a
  path is written as its name (`-o Results_Sep07_2 -f pep`, `-i input.fasta`). The preview box is
  editable and the text in it is the command that will run, so **the full path is written back
  before execution**: "shows short" must never become "runs short". Abbreviate only when the name
  is unambiguous — if the word also occurs elsewhere in the command, or two different paths share
  a name, keep the full path. Guessing a path wrong reads or writes the wrong file, which is much
  worse than one long line.

Reason: the command line is what the user reads, copies and turns into scripts. It is also
teaching material — a command stuffed with twenty defaults teaches nobody anything.

---

## P15. Ship the main programs, not the programs they use

An environment's command list contains only **the programs the user installed on purpose**. It
does not contain the ones pulled in alongside them, nor helper programs a package calls
internally.

Two filters, both using structural signals rather than tool lists:

| Layer | Signal | What it blocks |
|---|---|---|
| 1 | **package ownership**: keep only packages the user installed explicitly | commands that arrived as dependencies (on one machine a single library may quietly install command-line tools for authentication, compression, encoding and so on) |
| 2 | **within-package selection**: when a package provides many commands, keep only the main program that shares the package's name or is closely related to it | implementations and helpers the package uses internally |

Layer 2 is needed because some packages are themselves published as "a bundle of many
commands": their internal implementations also end up in `bin/`, they belong to the package, and
package ownership cannot filter them out.

Both criteria come from conda metadata (the explicitly installed set) and the package's `files`
manifest, so they contain no tool name, package name or environment name and hold on any
machine.

---

## P16. A dropdown must carry its candidate values

When a parameter is judged to be a choice, the control must offer **the options**, not an empty
text box for the user to guess into.

### The criterion must not depend on wording

Recognising "there is a list of candidate values here" **must not rely on English cue words**.

The anti-pattern (forbidden): maintaining a keyword list such as `options`, `choices`, `one of`,
`available`. Such a list is accumulated from the tools one has seen; another tool with another
phrasing breaks it, so entries keep getting appended — that is patching, not generality.

The right approach rests on **structure** and **content**, neither of which depends on the words
an author chose:

| Strength | Criterion | Note |
|---|---|---|
| strongest | **braces** `{a,b,c}` | a syntax symbol |
| strongest | **vertical bars** `a\|b\|c` | a syntax symbol |
| strong | **a comma-separated run of short identifiers**, at least 2, each ≤20 chars, no whitespace | a structural feature |
| strong | **a value in that run equals the parameter's default** | content consistency: the default must be one of the candidates |
| strong | **a value in that run also appears in the placeholder or the parameter name** | content consistency |

The point of criteria 3–5 is that candidates are not recognised by "which words were written
before them" but by "does this stretch of text have the shape of a set of options" and "is it
consistent with the parameter's own default or name".

### Degraded behaviour

When no candidates can be extracted the parameter falls back to the default rules (possibly
TEXT), and the values that were recognised are shown as pale hints inside the input box instead
of leaving an empty box for the user to guess into.

Reason: a choice parameter's values were defined explicitly by the tool's author, and missing
them leaves the user unable to know what to type. When they cannot be recognised it is better to
treat the parameter as text and show a hint than to offer a wrong candidate set.

---

## P17. Parse only once the information is complete

The verdicts on an option (type, candidates, default, range) **may only be reached after that
option's description is fully assembled**; they may not be reached while the option line is
being read.

Reason: help output routinely folds an option's description over several lines, and the key
information often lands on a continuation line. Deciding after reading only the first line means
deciding on a truncated description, which loses candidates, defaults or types that should have
been recognised. The symptom is "some tool's parameters are inexplicably text", and the cause
has nothing to do with the tool.

---

## P18. Parameters appear in several places, and all of them must be parsed

A command-line tool's help scatters parameter declarations across different places, and **every
one of those places must be parsed** — the "option detail section" alone is not enough.

The places observed (independent of tool names; they are formatting regularities):

| Place | Shape | Example |
|---|---|---|
| **usage line** | `tool [options] -f <dir>` | flags and placeholders only, **no description** |
| **usage line, in brackets** | `tool [-query input_file] [-out output_file]` | one pair of brackets per parameter |
| **sectioned detail block** | flag on one line, description **indented on the next** | `-query <File_In>` newline `Input file name` |
| **option section, same line** | `-in <inputfile>     Input file...` | the most common |
| **option section, continuation line** | description wrapped onto following lines | see P17 |

Parsing only one of them systematically misses parameters — above all **required parameters
declared only in the usage line** (input and output are often like this), and missing those makes
the tool unusable.

### The two meanings of brackets must be told apart

| Shape | Meaning | Handling |
|---|---|---|
| `[options]`, `[args]`, `[...]` | "there are other parameters here", not expanded | **skip** |
| `[-query input_file]` | one optional parameter | **extract** |

The criterion is the shape of the bracketed content: if a flag marker (`-x`) appears, extract;
if it is only a placeholder word, skip.

### Usage-line parameters carry little information

A usage line gives flags and placeholders only, with no description. Such parameters are still
shown (otherwise the user cannot see required parameters), but they must be **marked as having
no description**, so the user knows the information is limited instead of concluding that the
plugin failed to parse.

## P20. Filtering must trace back to a standard or a structure

When deciding that a name belongs to "a dependency or system tool" rather than to "a command
the user wants", the justification must be one of these:

| Allowed basis | Example |
|---|---|
| **structural feature** | leading underscore (internal entry point), `*-config` (build helper), platform triplets |
| **a closed set defined by an external standard** | POSIX utilities, GNU coreutils, compression-format tools |
| **a vendor or language namespace** | a prefix family shipped by some runtime |
| **relationship to a package the user installed explicitly** | package ownership (see P15) |

**Forbidden basis**: a set of names accumulated from one observation and kept alive by appending
entries. Such a list decays as environments change, and cannot explain why a given name is in it.

### Standard sets versus empirical lists

Both are formally a list of names, but they differ in kind:

- a **standard set** is defined by an external specification: it has a boundary, it can be
  cited, and it holds on any machine. POSIX says which utilities are standard — that is a
  checkable fact, not somebody's experience.
- an **empirical list** comes from "I saw this on one machine". It has no boundary, and the next
  person in a different environment meets names outside it.

In code, a standard set must appear as a **named rule** with its basis stated in a comment
(which standard, which vendor), not mixed into an anonymous blacklist.

---

## P21. Subcommands are learned on demand

A tool's subcommands **must not all be probed when the tool is opened**; only the one the user
actually opens is learned.

Reason: a tool may have dozens of subcommands, and probing all of them makes opening the tool
take minutes, for help output the user never looks at. The existence of subcommands can be read
from the main command's help; only their own options need separate probing.

---

## P22. The probe fast path must be decided by measurement, not guessed from file type

Probing (reading only help, only calls whose errors are observed) takes a **fast path**: it runs
the executable inside the environment directly, without going through conda's run entry.
**Actual execution** still goes through conda.

### Why they can differ

The two have different requirements:

| Purpose | Requirement |
|---|---|
| probing | the program only has to **start far enough to parse arguments**; a complete library environment is unnecessary |
| execution | the environment must be complete (libraries, variables), or the result cannot be trusted |

Measured: bypassing conda makes a single probe take **4 ms**, going through conda **2100 ms**
(700× slower). A command has dozens of parameters; probing through conda is not viable in time.

### The fast path must be verified, not inferred from file type

**Script-based programs can fail**: a Python entry point that imports its own package outside an
activated environment exits with `ModuleNotFoundError`, never parses a single argument, and the
probe captures a stack trace instead of help — so the tool is misjudged as "has no parameters".

**But file type cannot predict this**: in measurement, two programs that were both Python
scripts behaved differently, one failing and one working. File type simply does not correspond
to behaviour.

**The criterion**: for each tool, **try the fast path once** and check whether the output is an
environment failure (missing module, missing interpreter, failed library load, and so on). If so,
fall back to conda and **remember** the verdict, so each tool is judged only once.

### The verdict must be revocable

A verdict goes stale when the tool is upgraded or the environment rebuilt, so it is stored
alongside the cache and invalidated together with it when the environment fingerprint changes
(P23).

---

## P23. Discovery results are persisted and invalidated automatically by environment changes

The result of a scan (the tool list **and** each tool's parameter schema) must be written to
disk and be immediately usable the next time the plugin opens — **without the user clicking
detect again**.

Reason: a full scan takes about 19 seconds, and probing one tool's parameters takes several
seconds more. Making the user repeat that at every start passes an implementation shortcut on to
the user.

### Staleness must be detected automatically

The cache **must not be trusted blindly**. Tools get upgraded, environments get deleted or
created, and then the old result is simply wrong.

The criterion is **change in the environment itself**, not elapsed time:

| Signal | What a change means |
|---|---|
| number of environments | an environment was created or deleted |
| mtime of each environment directory | packages inside it were added, removed or changed |

An environment directory's mtime is updated whenever any package is installed, so it is a
reliable and cheap freshness signal. When a mismatch is detected the scan **re-runs
automatically**, with no user involvement.

### An explicit user request wins

Clicking "detect environments" always performs a full scan; that is how **newly installed**
tools are found. Automatic invalidation exists only to avoid needless waiting, not to replace
manual detection.

---

## P24. No re-probing within one session

A tool is probed **once per session**. Opening it again must show the result immediately.

Reason: users routinely compare several tools back and forth, and re-probing on every click
means waiting several seconds each time for a result that is necessarily identical.

---

## P25. Every way of attaching a value to a parameter must be recognised

A command-line parameter and its value can be joined in several forms, **every one of which must
be parsed**:

| Form | Example | Meaning |
|---|---|---|
| space-separated | `-o <f>` | takes a value |
| equals sign | `-INFILE=file.ext` | takes a value |
| equals sign, nothing after it | `-OUTFILE=` | takes a value; the author wrote no placeholder |
| colon separator | `-o <f> : desc` | takes a value (layout only) |
| no connector | `-QUICKTREE` | a switch |

Recognising only one form **systematically misjudges**: treat `-OUTFILE=` as a switch and the
user can never set an output file name again. The criterion is that `=` after a flag means
"takes a value", **even when nothing follows the equals sign** — writing the equals sign is the
author reserving a place for the value.

---

## P26. Angle brackets are the strongest placeholder signal

Single-letter placeholders such as `<s>`, `<x>` and `<f>` **are placeholders** and must be
accepted as a parameter's value slot.

An earlier rule required "at least two letters inside the angle brackets", to avoid taking the
first letter of a description (the `P` in `-h  Print this help`) for a placeholder. But that
concern only applies to **bare words without angle brackets**:

| Shape | Verdict |
|---|---|
| `<s>`, `<x>`, `<n>` | **a placeholder** (angle brackets = the author marked it) |
| `P` (a bare single letter) | not a placeholder |
| `FILE`, `NUM` | a placeholder |

In other words, "has angle brackets" and "all caps with at least 2 letters" are **two
independent conditions; either one suffices**.

---

## P27. Infer types from strong evidence in the description

When the placeholder is generic (`<s>`, `<x>`) or missing, infer the type from **strong
evidence** in the description:

| Evidence | Inference |
|---|---|
| default value in brackets, `[0.5]`, `[120]` | NUMBER |
| range, `(0<=x<=1)`, `[0..2]`, `(n>=1)` | NUMBER, plus the range |
| default that looks like a path, `[file.ext]`, `[/path]` | a path |

**Only formatted evidence in this category is accepted**, that is "default value" and "range".

Inferring from ordinary words in a description is forbidden (for example treating "file" as a
path): a description is prose, and a sentence mentioning "a file" does not mean the parameter is
a path — "writes 3 files by default" can easily occur in the description of a numeric parameter.
Formatted evidence has no such ambiguity.

---

## P28. Every verdict records its source, and sources have strengths

A parameter's type verdict **must record which signal produced it**, graded by signal strength:

| Strength | Source | Nature |
|---|---|---|
| 1 | **set by the user** | highest, overrides everything |
| 2 | **type column declaration** (a type word the author put in a dedicated column) | **a statement** |
| 3 | **value-slot placeholder** (`<int>`, `FILE`) | **a statement** |
| 4 | **candidate enumeration** | **a statement** |
| 5 | **range expression** | **a statement** |
| 6 | **shape of the default value** | strong evidence |
| 7 | **shape of an example value** (has an extension or path) | strong evidence |
| 8 | **a learned rule** | generalised from a user correction |
| 9 | **wording of the description** | **heuristic, weakest** |
| — | none | undecided |

### Strong signals win and are not overridden

The type column, placeholders, enumerations and ranges are **declarations the author wrote**,
not readings of a sentence. Once a declaration is seen, **no inference from the description may
override it**.

### The source must be visible to the user

The UI must be able to answer "where did this type come from". That does three things:

1. when the verdict is wrong, it shows **which step** went wrong, not just the wrong result
2. the user can **overrule** the verdict, and the overrule is remembered (P7)
3. when changing a rule, one can tell immediately which parameters it affects

### Why keep the weakest source, "wording of the description"

Most documentation carries no strong declaration; dropping the description entirely would leave
a large share of parameters as plain text. It is kept, but **labelled for what it is**: the user
sees that this is "a reading of the wording", rather than something conflated with a
declaration.

---

## P29. The plugin does not decide which parameters are required

**Which parameters must be filled in is the tool's business; the plugin does not judge it.**

The plugin turns an expressed intent into a command line. When the user omits something, the tool
reports an accurate error, including conditional requirements **that depend on other
parameters** — requirements the plugin cannot see.

### Why the plugin cannot decide

The plugin can only guess from **how** the help text is written, and "required" is written in
every imaginable way:

| Wording | Real example | Problem |
|---|---|---|
| brackets | `Usage: prog -i FILE [-n N]` | example lines and prose lines contain flags too |
| description keyword | `provided "suffix" (required) matches...` | `required` modifies **the word suffix**, not the option |
| conditional | `Required for any pixel format with an alpha channel` | has a precondition; not unconditional |
| a parameter seen in an example | `prog -in a.fa -out b.fa -gappyout` | an example is a demonstration, not a declaration |

Measured: of 65 options marked as required, **50 (76.9%) never appear in a real usage
declaration**.

### Guessing wrong costs far more than not guessing

| Failure direction | Consequence |
|---|---|
| missing a required parameter | the user runs it unfilled → **the tool reports an accurate error** |
| **wrongly marking one required** | the user is blocked, and **the tool becomes completely unusable** |

The second is the real problem: calls that **need no other parameter at all**, such as
`trimal -h` or `prog --version`, get blocked. **Every tool has help and version commands**, so
this mistake has an extremely wide blast radius.

This follows the trade-off in P6 (degrade rather than disable).

### What is kept

Usage declarations **are still parsed**, because they are often the **only** place a parameter is
declared (measured: 100 parameters appear only in usage declarations, across 31.8% of the
documents). What is no longer done is inferring "required" from the brackets.

## P30. Parameters come only from declarations, never from prose

A parameter name may be extracted from exactly two kinds of place:

| Place | Shape |
|---|---|
| **option line** | a flag at the start of a line (possibly indented or wrapped in brackets), followed by a description column |
| **usage line** | an in-line token that starts with a flag and is preceded by whitespace or a separator |

**Nothing in prose is a parameter**, even when it contains a hyphen.

### Word boundaries are mandatory

The regular expression that extracts flags **must** require the flag to be preceded by the start
of a line or a separator (whitespace, `[`, `(`, `,`, `|`).

**`\b` cannot be used**: a hyphen is itself a word boundary — in `user-friendly` there is a `\b`
between `r` and `-`, so `\b` cannot exclude it.

```
A user-friendly toolkit for whole-genome duplications
         └──────┬──┘         └────┬─────┘
          "-friendly"          "-genome"      ← artefacts of a missing word boundary
```

### The risk is widely distributed

Measured (108 samples):

| Metric | Value |
|---|---|
| documents containing hyphenated compounds | **71.7%** |
| prose lines containing hyphens | **1574 lines** |

Compounds such as `user-friendly`, `state-of-the-art` and `read-only` are extremely common in
English technical documentation, so this is not a problem of a few tools; it is a problem for
**any** place that extracts tokens from prose.

### Prose lines must not be taken for usage lines

Recognising an unlabelled usage line must exclude prose. The criteria:

1. **it starts with the command name, and a separator follows** — `prog -f <dir>` qualifies,
   `prog(Full Name): A user-friendly...` does not (punctuation follows the name)
2. **it has no sentence structure** — a line with conjunctions such as and/for/the between
   ordinary words is a sentence

**Note**: recognising usage lines **must not rely on criteria such as "contains lowercase
words"**, because every English sentence satisfies that.

### Better to miss than to misjudge

Tightening the criteria misses some genuine usage lines, so their parameters go missing. That is
the **acceptable** direction:

| Failure direction | Consequence |
|---|---|
| missing a real parameter | the user types one value by hand |
| **inventing a fake parameter** | the user faces a **required field that cannot be satisfied** and the tool will not run |

The second is exactly what this principle exists to prevent.

---

## P31. Layout is a hint, not a fact

Help output lays out flags, value slots and descriptions in **aligned columns**. That column is
only a typesetting result, **not a fact about the content**. Splitting on it must obey three
rules:

| Situation | Example | Handling |
|---|---|---|
| the value slot **overflows** the column | `-icl IMPROVEDCOLLINEARITY` | move the split point to a token boundary; never split inside a word |
| the description is on the **next line** | `-km KARYOTYPE_MAPPING` newline `Mapping ...` | everything after the flag on this line is the value slot |
| this line is **shorter** than the description column | as above | do not skip splitting just because the line is short |

### Why hard column splitting fails

Hard column splitting cuts the value slot **in half**:

```
-icl IMPROVEDCOLLINEARITY
           ^ column position
  → value slot = "IMPROVEDCOLLINEAR", description = "ITY improved version..."
```

Once truncated, the value slot matches no placeholder shape, so the parameter is judged to take
no value — a parameter that should hold a file name turns into a switch. It triggers whenever an
author writes a long name, so it is not a rare case.

### The criteria

**A column position may only be used to "lean", never to "decide"**:

1. if the column position falls inside a token (neither side blank), move the split point **right,
   to the next separator**
2. if the column position lies beyond the end of the line, the description is elsewhere and
   **all** of this line is the value slot

---

## P32. How to tell whether something is general

- no concrete tool name can be found in the code
- adding a tool requires no code change
- with every environment deleted, the program still starts and gives a clear message

Any feature that only holds for particular tools shows that generality has not been reached.

### Every fix must be tested for generality

When a defect is found, first measure its **distribution** in the sample, then decide the scope
of the fix.

Fixing only the tool that triggered it treats a general defect as a local one — the same defect
keeps happening on other tools, while the user believes it was fixed.

How to measure: batch-count the occurrence ratio of that shape in real samples. The distribution
of hyphen mis-extraction, for example, was 71.7% of documents and 1574 lines, which is what made
it a general defect rather than an isolated one.



