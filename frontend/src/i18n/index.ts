import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUS from './locales/en-US';
import zhCN from './locales/zh-CN';

export const APP_LANGUAGES = ['zh-CN', 'en-US'] as const;
export type AppLanguage = typeof APP_LANGUAGES[number];

export const LANGUAGE_STORAGE_KEY = 'autospec.language';
export const DEFAULT_LANGUAGE: AppLanguage = 'en-US';

export function normalizeLanguage(language?: string | null): AppLanguage | null {
  if (!language) return null;
  const normalized = language.toLowerCase();
  if (normalized.startsWith('zh')) return 'zh-CN';
  if (normalized.startsWith('en')) return 'en-US';
  return null;
}

export function resolveInitialLanguage(
  storedLanguage?: string | null,
  browserLanguage?: string | null
): AppLanguage {
  return normalizeLanguage(storedLanguage)
    ?? normalizeLanguage(browserLanguage)
    ?? DEFAULT_LANGUAGE;
}

function getStoredLanguage(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
  } catch {
    return null;
  }
}

function getBrowserLanguage(): string | null {
  return typeof navigator === 'undefined' ? null : navigator.language;
}

function getRuntimeLanguage(): string | null {
  const isTestEnvironment = typeof process !== 'undefined' && process.env.NODE_ENV === 'test';
  return isTestEnvironment ? DEFAULT_LANGUAGE : getBrowserLanguage();
}

interface LanguagePersistenceTarget {
  storage?: Pick<Storage, 'setItem'>;
  documentElement?: { lang: string };
}

export function persistLanguage(language: string, target?: LanguagePersistenceTarget) {
  const normalized = normalizeLanguage(language) ?? DEFAULT_LANGUAGE;
  const documentElement = target?.documentElement
    ?? (typeof document !== 'undefined' ? document.documentElement : undefined);
  if (documentElement) {
    documentElement.lang = normalized;
  }
  let storage = target?.storage;
  if (!storage && typeof window !== 'undefined') {
    try {
      storage = window.localStorage;
    } catch {
      storage = undefined;
    }
  }
  if (storage) {
    try {
      storage.setItem(LANGUAGE_STORAGE_KEY, normalized);
    } catch {
      // Storage can be unavailable in privacy-restricted browser contexts.
    }
  }
}

i18n.on('languageChanged', persistLanguage);

void i18n
  .use(initReactI18next)
  .init({
    resources: {
      'en-US': { translation: enUS },
      'zh-CN': { translation: zhCN }
    },
    lng: resolveInitialLanguage(getStoredLanguage(), getRuntimeLanguage()),
    fallbackLng: DEFAULT_LANGUAGE,
    supportedLngs: APP_LANGUAGES,
    load: 'currentOnly',
    initAsync: false,
    interpolation: {
      escapeValue: false
    },
    returnNull: false
  });

export function currentLanguage(): AppLanguage {
  return normalizeLanguage(i18n.resolvedLanguage ?? i18n.language) ?? DEFAULT_LANGUAGE;
}

export default i18n;
