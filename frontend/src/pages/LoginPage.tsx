import { LoginOutlined } from '@ant-design/icons';
import { Button, Form, Input, Typography, message } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { login, writeSession } from '../api/auth';

interface LoginValues {
  username: string;
  password: string;
}

function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  async function handleFinish(values: LoginValues) {
    setSubmitting(true);
    try {
      const session = await login(values.username, values.password);
      writeSession(session);
      navigate('/');
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('login.failed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="workspace" id="main-content">
      <section className="panel input-panel">
        <Typography.Title level={1}>{t('login.title')}</Typography.Title>
        <Form
          layout="vertical"
          onFinish={handleFinish}
          requiredMark="optional"
        >
          <Form.Item
            name="username"
            label={t('login.username')}
            rules={[{ required: true, message: t('login.usernameRequired') }]}
          >
            <Input size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('login.password')}
            rules={[{ required: true, message: t('login.passwordRequired') }]}
          >
            <Input.Password size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" icon={<LoginOutlined />} loading={submitting}>
            {t('login.submit')}
          </Button>
        </Form>
      </section>
    </main>
  );
}

export default LoginPage;
