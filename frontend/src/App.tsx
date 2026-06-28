import { FileTextOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Layout } from 'antd';
import { Link, Route, BrowserRouter as Router, Routes } from 'react-router-dom';
import HomePage from './pages/HomePage';
import ProjectDetailPage from './pages/ProjectDetailPage';

const { Header } = Layout;

function App() {
  return (
    <Router>
      <Layout className="app-shell">
        <Header className="app-header">
          <Link to="/" className="brand">
            <FileTextOutlined />
            <span>AutoSpec</span>
          </Link>
          <Button type="primary" icon={<PlusOutlined />}>
            <Link to="/">New</Link>
          </Button>
        </Header>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
        </Routes>
      </Layout>
    </Router>
  );
}

export default App;
