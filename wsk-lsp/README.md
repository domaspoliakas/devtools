# WSK LSP

A Language Server Protocol (LSP) server for precog v3 precog-source-web. Currently supports:
- Document Symbols:
  - `configSchema` entries
  - datasets

## How do I use it?!

### Prerequisites

First you need to build the dependencies:
```bash
git submodule init && git submodule update
cd tree-sitter && make
cd tree-sitter-json && npm install && npm run build
```

You will also need `clang` to be installed, and it needs to be >=13.

### Building an executable

Once you have those set up you can link the project:
```bash
sbt nativeLink
```

This will output the executable into the `./target/scala-3.2.0/wsk-lsp-out`

The next step is to tell your editor to you use it

### Editors

#### Neovim

I use neovim, so that's the only one I tried. 

1) Install `nvim-lspconfig` plugin
2) Configure the LSP like so (assumes lua config):
```lua
local lspconfig = require 'lspconfig'
local configs = require 'lspconfig.configs'
local util = require 'lspconfig.util'

if not configs.wsk_lsp then 
  configs.wsk_lsp = {
    default_config = {
      cmd = { '<path to where you put this repo>/wsk-lsp/target/scala-3.2.0/wsk-lsp-out' },
      filetypes = { 'json' },
      root_dir = util.root_pattern('web-source-kinds'),
      settings = {}
    };
  }
end

lspconfig.wsk_lsp.setup{}
```

This will make neovim launch this LSP for json files under the folder `web-source-kinds`. If you edit your web-source-kinds in different locations - you might need to amend this configuration slightly.

## TODO

- [ ] Fix bug where config schema entries with empty `fieldDescription` don't get picked up
- [ ] VSCode support
- [ ] Emacs support?
- [ ] Add a more useful hover (e.g. for UUIDs to look up the dataset and its parents that match the UUID)
- [ ] Go-to-definition for UUIDs (taking you to the dataset) or config schema variables
- [ ] Go-to-references for datasets (taking)
- [ ] Incremental parsing

### For the future

- Diagnostics
- Completions? 
- Code actions:
  - Generate a new dataset
  - Wrap dataset in a microservice (Credit for the idea goes to Rintcius on this one!)
- Interpolated string integration into treesitter
  - treesitter parser for interpolated strings
  - highlighting based on the above
  - use treesitter queries to extract things out of strings 

