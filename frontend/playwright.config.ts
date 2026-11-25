import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  testMatch:
    process.env.CAPYIOT_LEGACY_TESTS === 'true'
      ? /.*\.spec\.ts/
      : /(video-platform|agent-draft|agent-chat(?:-live)?)\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: 'list',
  use: {
    baseURL: process.env.CAPYIOT_BASE_URL ?? 'http://127.0.0.1:8088',
    browserName: 'chromium',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH },
    trace: 'retain-on-failure',
  },
})
