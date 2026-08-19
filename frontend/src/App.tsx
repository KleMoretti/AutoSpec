import { FileTextOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Layout } from 'antd';
import { Link, Navigate, Route, BrowserRouter as Router, Routes } from 'react-router-dom';
import { readSession } from './api/auth';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import ProjectDetailPage from './pages/ProjectDetailPage';

const { Header } = Layout;

function App() {
  const session = readSession();

  return (
    <Router>
      <Layout className="app-shell">
        <a className="skip-link" href="#main-content">Skip to main content</a>
        <Header className="app-header">
          <Link to="/" className="brand">
            <FileTextOutlined />
            <span>AutoSpec</span>
          </Link>
          <Button icon={<PlusOutlined />}>
            <Link to="/">Projects</Link>
          </Button>
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
