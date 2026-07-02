import { LoginOutlined } from '@ant-design/icons';
import { Button, Form, Input, Typography, message } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, writeSession } from '../api/auth';

interface LoginValues {
  username: string;
  password: string;
}

function LoginPage() {
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  async function handleFinish(values: LoginValues) {
    setSubmitting(true);
    try {
      const session = await login(values.username, values.password);
      writeSession(session);
      navigate('/');
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="workspace">
      <section className="panel input-panel">
        <Typography.Title level={1}>Sign in</Typography.Title>
        <Form
          layout="vertical"
          onFinish={handleFinish}
          requiredMark="optional"
          initialValues={{ username: 'owner', password: 'owner-pass' }}
        >
          <Form.Item name="username" label="Username" rules={[{ required: true, message: 'Username is required' }]}>
            <Input size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="Password" rules={[{ required: true, message: 'Password is required' }]}>
            <Input.Password size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" icon={<LoginOutlined />} loading={submitting}>
            Sign in
          </Button>
        </Form>
      </section>
    </main>
  );
}

export default LoginPage;
