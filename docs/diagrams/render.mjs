// Regenerate the README diagram SVGs from the .mmd sources with beautiful-mermaid.
//   npm install beautiful-mermaid && node docs/diagrams/render.mjs
// Produces <name>-light.svg and <name>-dark.svg (github themes) for each .mmd here.
import * as bm from 'beautiful-mermaid';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = dirname(fileURLToPath(import.meta.url));
const variants = { light: bm.THEMES['github-light'], dark: bm.THEMES['github-dark'] };

for (const f of readdirSync(dir).filter(f => f.endsWith('.mmd'))) {
  const name = f.replace(/\.mmd$/, '');
  const src = readFileSync(join(dir, f), 'utf8');
  for (const [variant, theme] of Object.entries(variants)) {
    const svg = bm.renderMermaidSVG(src, theme);
    if (!/^\s*<svg/.test(svg)) throw new Error(`${f} ${variant}: not an SVG`);
    writeFileSync(join(dir, `${name}-${variant}.svg`), svg);
    console.log(`${name}-${variant}.svg  ${svg.length} bytes`);
  }
}
