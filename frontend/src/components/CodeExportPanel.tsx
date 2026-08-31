import { CodeOutlined, DownloadOutlined } from '@ant-design/icons';
import { Button, Space, Typography, message } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { generateCodeSkeleton } from '../api/workflow';

interface CodeExportPanelProps {
  projectId: number;
  disabled?: boolean;
  onGenerated?: () => Promise<void> | void;
}

function CodeExportPanel({ projectId, disabled, onGenerated }: CodeExportPanelProps) {
  const { t } = useTranslation();
  const [exporting, setExporting] = useState(false);

  async function handleGenerate() {
    setExporting(true);
    try {
      const response = await generateCodeSkeleton(projectId);
      const bytes = Uint8Array.from(atob(response.content), (char) => char.charCodeAt(0));
      downloadBlob(bytes, response.fileName, response.mediaType);
      message.success(t('codeExport.success'));
      await onGenerated?.();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('codeExport.failed'));
    } finally {
      setExporting(false);
    }
  }

  return (
    <section className="panel">
      <Space direction="vertical" size={12}>
        <Space>
          <CodeOutlined />
          <Typography.Title level={3}>{t('codeExport.title')}</Typography.Title>
        </Space>
        <Button
          type="primary"
          icon={<DownloadOutlined />}
          loading={exporting}
          disabled={disabled}
          onClick={handleGenerate}
        >
          {t('codeExport.exportZip')}
        </Button>
      </Space>
    </section>
  );
}

function downloadBlob(content: BlobPart, fileName: string, type: string) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

export default CodeExportPanel;
