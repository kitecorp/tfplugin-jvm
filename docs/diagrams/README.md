# README diagrams

The architecture diagrams in the top-level `README.md` are pre-rendered SVGs,
not live mermaid blocks: GitHub renders live mermaid with its own dated theme,
so the diagrams are rendered with [beautiful-mermaid](https://github.com/lukilabs/beautiful-mermaid)
using its `github-light` / `github-dark` themes and embedded via `<picture>`
so they follow the viewer's GitHub theme.

Sources are the `.mmd` files here. To regenerate the SVGs after editing a source:

```bash
npm install beautiful-mermaid
node docs/diagrams/render.mjs
```

This writes `<name>-light.svg` and `<name>-dark.svg` for each `.mmd`. `node_modules`
is intentionally not committed; the diagrams change rarely.
