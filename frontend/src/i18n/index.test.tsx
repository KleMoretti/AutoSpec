import { createInstance } from 'i18next';
import { renderToStaticMarkup } from 'react-dom/server';
import { I18nextProvider } from 'react-i18next';
import { describe, expect, it, vi } from 'vitest';
import LanguageSwitcher from '../components/LanguageSwitcher';
import {
  LANGUAGE_STORAGE_KEY,
  normalizeLanguage,
  persistLanguage,
  resolveInitialLanguage,
  type AppLanguage
} from '.';
import enUS from './locales/en-US';
import zhCN from './locales/zh-CN';

describe('application i18n', () => {
  it('prefers a stored language and falls back to the supported browser language', () => {
    expect(resolveInitialLanguage('zh-CN', 'en-US')).toBe('zh-CN');
    expect(resolveInitialLanguage(null, 'zh-TW')).toBe('zh-CN');
    expect(resolveInitialLanguage('fr-FR', 'en-GB')).toBe('en-US');
    expect(normalizeLanguage('de-DE')).toBeNull();
  });

  it('keeps the English and Chinese resource trees in sync', () => {
    expect(resourceKeys(zhCN)).toEqual(resourceKeys(enUS));
  });

  it('persists the normalized choice and updates the document language', () => {
    const storage = { setItem: vi.fn() };
    const documentElement = { lang: '' };

    persistLanguage('zh-TW', { storage, documentElement });

    expect(storage.setItem).toHaveBeenCalledWith(LANGUAGE_STORAGE_KEY, 'zh-CN');
    expect(documentElement.lang).toBe('zh-CN');
  });

  it('renders the switcher in the active language', async () => {
    const english = await renderSwitcher('en-US');
    expect(english).toContain('Language');
    expect(english).toContain('English');

    const chinese = await renderSwitcher('zh-CN');
    expect(chinese).toContain('语言');
    expect(chinese).toContain('中文');
  });
});

function resourceKeys(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [prefix];
  return Object.entries(value)
    .flatMap(([key, child]) => resourceKeys(child, prefix ? `${prefix}.${key}` : key))
    .sort();
}

async function renderSwitcher(language: AppLanguage): Promise<string> {
  const instance = createInstance();
  await instance.init({
    resources: {
      'en-US': { translation: enUS },
      'zh-CN': { translation: zhCN }
    },
    lng: language,
    fallbackLng: 'en-US',
    initAsync: false
  });
  return renderToStaticMarkup(
    <I18nextProvider i18n={instance}>
      <LanguageSwitcher />
    </I18nextProvider>
  );
}
