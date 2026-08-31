import { TranslationOutlined } from '@ant-design/icons';
import { Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { DEFAULT_LANGUAGE, normalizeLanguage, type AppLanguage } from '../i18n';

function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const activeLanguage = normalizeLanguage(i18n.resolvedLanguage ?? i18n.language) ?? DEFAULT_LANGUAGE;

  return (
    <Select<AppLanguage>
      className="language-switcher"
      aria-label={t('language.switcherLabel')}
      value={activeLanguage}
      suffixIcon={<TranslationOutlined aria-hidden="true" />}
      onChange={(language) => void i18n.changeLanguage(language)}
      options={[
        { value: 'zh-CN', label: t('language.chinese') },
        { value: 'en-US', label: t('language.english') }
      ]}
    />
  );
}

export default LanguageSwitcher;
