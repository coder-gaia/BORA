import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  retries: 0,
  use: {
    baseURL: process.env.BORA_PREVIEW_URL || 'http://localhost:5173',
    browserName: 'chromium',
    channel: process.env.BORA_BROWSER_CHANNEL || undefined,
    trace: 'retain-on-failure',
  },
});
