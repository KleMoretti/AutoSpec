import { PlayCircleOutlined } from '@ant-design/icons';
import { Button, Form, Input, Typography, message } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createProject } from '../api/projects';

const { TextArea } = Input;

interface FormValues {
  name: string;
  requirement: string;
}

function HomePage() {
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  async function handleFinish(values: FormValues) {
    setSubmitting(true);
    try {
      const project = await createProject(values);
      navigate(`/projects/${project.projectId}`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Generation failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="workspace">
      <section className="panel input-panel">
        <Typography.Title level={1}>New project</Typography.Title>
        <Form layout="vertical" onFinish={handleFinish} requiredMark="optional">
          <Form.Item
            name="name"
            label="Project name"
            rules={[{ required: true, message: 'Project name is required' }]}
          >
            <Input size="large" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="requirement"
            label="Requirement"
            rules={[{ required: true, message: 'Requirement is required' }]}
          >
            <TextArea rows={9} showCount maxLength={2000} />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            size="large"
            icon={<PlayCircleOutlined />}
            loading={submitting}
          >
            Create project
          </Button>
        </Form>
      </section>
    </main>
  );
}

export default HomePage;
