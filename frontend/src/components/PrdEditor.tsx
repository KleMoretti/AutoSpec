import { CheckCircleOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, Button, Input, Space, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { ArtifactResponse } from '../api/projects';

const { TextArea } = Input;

interface PrdEditorProps {
  artifact: ArtifactResponse;
  onSave: (content: string) => Promise<void>;
  onApprove: () => Promise<void>;
}

export function isValidJson(content: string): boolean {
  try {
    JSON.parse(content);
    return true;
  } catch {
    return false;
  }
}

function PrdEditor({ artifact, onSave, onApprove }: PrdEditorProps) {
  const [draftContent, setDraftContent] = useState(artifact.content);
  const [saving, setSaving] = useState(false);
  const [approving, setApproving] = useState(false);
  const validJson = useMemo(() => isValidJson(draftContent), [draftContent]);

  useEffect(() => {
    setDraftContent(artifact.content);
  }, [artifact.id, artifact.content]);

  async function handleSave() {
    setSaving(true);
    try {
      await onSave(draftContent);
    } finally {
      setSaving(false);
    }
  }

  async function handleApprove() {
    setApproving(true);
    try {
      await onApprove();
    } finally {
      setApproving(false);
    }
  }

  return (
    <section className="panel prd-editor">
      <div className="section-heading">
        <div>
          <Typography.Title level={2}>PRD review</Typography.Title>
          <Space size={8} wrap>
            <Tag>{artifact.status ?? 'PENDING_REVIEW'}</Tag>
            <Typography.Text className="muted">v{artifact.version}</Typography.Text>
            {artifact.sourceAgent ? <Typography.Text className="muted">{artifact.sourceAgent}</Typography.Text> : null}
            {artifact.updatedAt ? <Typography.Text className="muted">Updated {artifact.updatedAt}</Typography.Text> : null}
          </Space>
        </div>
        <Space wrap>
          <Button icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
            Save
          </Button>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            loading={approving}
            disabled={!validJson}
            onClick={handleApprove}
          >
            Approve
          </Button>
        </Space>
      </div>
      {!validJson ? (
        <Alert
          type="error"
          showIcon
          message="Invalid JSON"
          description="Fix the PRD JSON before approval. You can still save a draft for later correction."
        />
      ) : null}
      <TextArea
        aria-label="PRD JSON content"
        value={draftContent}
        onChange={(event) => setDraftContent(event.target.value)}
        rows={18}
        spellCheck={false}
        className="json-editor"
      />
    </section>
  );
}

export default PrdEditor;
