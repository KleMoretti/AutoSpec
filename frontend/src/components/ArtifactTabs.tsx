import { Empty, Tabs, Typography } from 'antd';
import type { ArtifactResponse } from '../api/projects';
import FrontendSkeletonPreview from './FrontendSkeletonPreview';

interface ArtifactTabsProps {
  artifacts: ArtifactResponse[];
}

const TYPE_LABELS: Record<string, string> = {
  PRD: 'PRD',
  ARCHITECTURE_DESIGN: 'Architecture',
  BACKEND_DESIGN: 'Backend design',
  FRONTEND_SKELETON: 'Frontend skeleton',
  REVIEW_REPORT: 'Review report'
};

function ArtifactTabs({ artifacts }: ArtifactTabsProps) {
  if (artifacts.length === 0) {
    return (
      <section className="panel">
        <Typography.Title level={2}>Artifacts</Typography.Title>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No artifacts" />
      </section>
    );
  }

  return (
    <section className="panel">
      <Typography.Title level={2}>Artifacts</Typography.Title>
      <Tabs
        items={artifacts.map((artifact) => ({
          key: String(artifact.id),
          label: TYPE_LABELS[artifact.type] ?? artifact.type,
          children: (
            <div className="artifact-pane">
              <Typography.Text strong>{artifact.title}</Typography.Text>
              {artifact.type === 'FRONTEND_SKELETON' ? (
                <FrontendSkeletonPreview content={artifact.content} />
              ) : (
                <pre>{formatJson(artifact.content)}</pre>
              )}
            </div>
          )
        }))}
      />
    </section>
  );
}

function formatJson(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

export default ArtifactTabs;
