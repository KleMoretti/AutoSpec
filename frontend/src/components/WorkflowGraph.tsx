import { ApartmentOutlined } from '@ant-design/icons';
import { Space, Tag, Typography } from 'antd';
import type { WorkflowSnapshotResponse } from '../api/v3';

interface WorkflowGraphProps {
  workflow: WorkflowSnapshotResponse | null;
}

function WorkflowGraph({ workflow }: WorkflowGraphProps) {
  if (!workflow) {
    return null;
  }

  return (
    <section className="panel">
      <Space direction="vertical" size={12} className="full-width">
        <Space>
          <ApartmentOutlined />
          <Typography.Title level={3}>Workflow</Typography.Title>
          <Tag>{workflow.version}</Tag>
        </Space>
        <div className="workflow-grid">
          {workflow.nodes.map((node) => (
            <div className="workflow-node" key={node.id}>
              <Typography.Text strong>{node.label ?? node.id}</Typography.Text>
              {node.artifactType ? <Typography.Text className="muted">{node.artifactType}</Typography.Text> : null}
            </div>
          ))}
        </div>
      </Space>
    </section>
  );
}

export default WorkflowGraph;
