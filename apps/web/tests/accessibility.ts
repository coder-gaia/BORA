import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { expect, type Page, type TestInfo } from '@playwright/test';
import type { AxeResults, RunOptions } from 'axe-core';

export async function auditAccessibility(page: Page, testInfo: TestInfo, state: string) {
  const axeUrl = new URL('/__bora-accessibility.js', page.url()).href;
  const source = await readFile(
    createRequire(import.meta.url).resolve('axe-core/axe.min.js'),
    'utf8',
  );
  await page.route(axeUrl, (route) =>
    route.fulfill({ contentType: 'application/javascript; charset=utf-8', body: source }),
  );
  // A same-origin external script also works with the real production script-src CSP.
  // No bypassCSP or disabling rules to make a page appear accessible.
  await page.addScriptTag({ url: axeUrl });
  const screenshot = testInfo.outputPath(`${state}.png`);
  await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' });
  await testInfo.attach(state, { path: screenshot, contentType: 'image/png' });
  const accessibility = await page.evaluate(async () => {
    const axe = (
      window as Window & {
        axe: { run: (document: Document, options: RunOptions) => Promise<AxeResults> };
      }
    ).axe;
    return axe.run(document, { resultTypes: ['violations', 'incomplete'] });
  });
  const overflow = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  const incompleteContext = await page.evaluate((checks) => {
    return checks.flatMap((check) =>
      check.nodes.map((node) => {
        const selector = node.target[0];
        const element = typeof selector === 'string' ? document.querySelector(selector) : null;
        const backgrounds = [];
        for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
          const style = getComputedStyle(ancestor);
          backgrounds.push({
            tag: ancestor.tagName,
            class: ancestor.className,
            color: style.backgroundColor,
            image: style.backgroundImage,
            opacity: style.opacity,
          });
          if (style.backgroundColor !== 'rgba(0, 0, 0, 0)') break;
        }
        const style = element && getComputedStyle(element);
        return {
          check: check.id,
          target: node.target,
          text: element?.textContent,
          foreground: style?.color,
          fontSize: style?.fontSize,
          fontWeight: style?.fontWeight,
          backgrounds,
        };
      }),
    );
  }, accessibility.incomplete);
  await testInfo.attach(`${state}-axe`, {
    body: JSON.stringify({ state, overflow, accessibility, incompleteContext }, null, 2),
    contentType: 'application/json',
  });
  expect(overflow.document, `Horizontal overflow in ${state}`).toBeLessThanOrEqual(
    overflow.viewport,
  );
  expect(accessibility.violations, `Axe violations in ${state}`).toEqual([]);
  // Incomplete checks remain in the report for explicit human review. Zero automated
  // violations alone is not a claim that every accessibility criterion was verified.
  return accessibility;
}
