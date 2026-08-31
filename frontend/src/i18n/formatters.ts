import type { TFunction } from 'i18next';
import { DEFAULT_LANGUAGE, normalizeLanguage } from '.';

export function translateEnum(
  t: TFunction,
  group: string,
  value?: string | null,
  fallback = '--'
): string {
  if (!value) return fallback;
  return t(`${group}.${value}`, { defaultValue: value });
}

export function localeFor(language?: string): string {
  return normalizeLanguage(language) ?? DEFAULT_LANGUAGE;
}

export function formatNumber(value: number, language?: string): string {
  return new Intl.NumberFormat(localeFor(language)).format(value);
}

export function formatUsd(value: number, language?: string, fractionDigits = 4): string {
  return new Intl.NumberFormat(localeFor(language), {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  }).format(value);
}

export function formatDateTime(value: string, language?: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(localeFor(language), {
    dateStyle: 'medium',
    timeStyle: 'medium'
  }).format(date);
}
