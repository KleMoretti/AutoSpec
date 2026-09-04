import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import { I18nextProvider, useTranslation } from 'react-i18next';
import App from './App';
import i18n, { currentLanguage } from './i18n';
import './styles.css';

function LocalizedApp() {
  useTranslation();
  const language = currentLanguage();

  return (
    <ConfigProvider
      locale={language === 'zh-CN' ? zhCN : enUS}
      theme={{
        token: {
          colorPrimary: '#1f6feb',
          borderRadius: 6
        }
      }}
    >
      <App />
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <I18nextProvider i18n={i18n}>
      <LocalizedApp />
    </I18nextProvider>
  </React.StrictMode>
);
