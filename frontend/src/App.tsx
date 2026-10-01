import { FileTextOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Layout, Space } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, Route, BrowserRouter as Router, Routes } from 'react-router-dom';
import { readSession, subscribeSession, type SessionUser } from './api/auth';
import LanguageSwitcher from './components/LanguageSwitcher';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import ProjectDetailPage from './pages/ProjectDetailPage';

const { Header } = Layout;

function App() {
  const { t } = useTranslation();
  const [session, setSession] = useState<SessionUser | null>(() => readSession());

  useEffect(() => subscribeSession(setSession), []);

  return (
    <Router>
      <Layout className="app-shell">
        <a className="skip-link" href="#main-content">{t('app.skipToContent')}</a>
        <Header className="app-header">
          <Link to="/" className="brand">
            <FileTextOutlined />
            <span>AutoSpec</span>
          </Link>
          <Space className="header-actions" size={8}>
            <LanguageSwitcher />
            <Button icon={<PlusOutlined />} aria-label={t('app.projects')}>
              <Link to="/"><span className="header-projects-label">{t('app.projects')}</span></Link>
            </Button>
          </Space>
        </Header>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={session ? <HomePage /> : <Navigate to="/login" replace />} />
          <Route
            path="/projects/:projectId"
            element={session ? <ProjectDetailPage /> : <Navigate to="/login" replace />}
          />
        </Routes>
      </Layout>
    </Router>
  );
}

export default App;
