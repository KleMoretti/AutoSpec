import { Alert, Descriptions, Empty, List, Space, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

interface FrontendSkeletonArtifact {
  routes?: Array<{ path?: string; page?: string }>;
  pages?: Array<{ name?: string; purpose?: string; components?: string[] }>;
  components?: Array<{ name?: string; type?: string; props?: string[]; state?: string[] }>;
  api_bindings?: Array<{ method?: string; path?: string; consumer?: string }>;
  apiBindings?: Array<{ method?: string; path?: string; consumer?: string }>;
}

interface FrontendSkeletonPreviewProps {
  content: string;
}

function FrontendSkeletonPreview({ content }: FrontendSkeletonPreviewProps) {
  const { t } = useTranslation();
  const parsed = parseSkeleton(content);
  if (!parsed.ok) {
    return <Alert type="warning" showIcon message={t('skeleton.invalidJson')} description={content} />;
  }

  const skeleton = parsed.value;
  const apiBindings = skeleton.api_bindings ?? skeleton.apiBindings ?? [];

  return (
    <Space direction="vertical" size={20} className="full-width skeleton-preview">
      <div>
        <Typography.Text strong>{t('skeleton.routes')}</Typography.Text>
        {skeleton.routes?.length ? (
          <Table
            size="small"
            pagination={false}
            rowKey={(record) => `${record.path}-${record.page}`}
            dataSource={skeleton.routes}
            columns={[
              { title: t('skeleton.path'), dataIndex: 'path' },
              { title: t('skeleton.page'), dataIndex: 'page' }
            ]}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('skeleton.noRoutes')} />
        )}
      </div>
      <div>
        <Typography.Text strong>{t('skeleton.pages')}</Typography.Text>
        <List
          dataSource={skeleton.pages ?? []}
          locale={{ emptyText: t('skeleton.noPages') }}
          renderItem={(page) => (
            <List.Item>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label={t('skeleton.name')}>{page.name}</Descriptions.Item>
                <Descriptions.Item label={t('skeleton.purpose')}>{page.purpose}</Descriptions.Item>
                <Descriptions.Item label={t('skeleton.components')}>{renderTags(page.components, t('skeleton.none'))}</Descriptions.Item>
              </Descriptions>
            </List.Item>
          )}
        />
      </div>
      <div>
        <Typography.Text strong>{t('skeleton.components')}</Typography.Text>
        <Table
          size="small"
          pagination={false}
          rowKey={(record) => record.name ?? Math.random().toString(36)}
          dataSource={skeleton.components ?? []}
          columns={[
            { title: t('skeleton.name'), dataIndex: 'name' },
            { title: t('skeleton.type'), dataIndex: 'type' },
            { title: t('skeleton.props'), render: (_, record) => renderTags(record.props, t('skeleton.none')) },
            { title: t('skeleton.state'), render: (_, record) => renderTags(record.state, t('skeleton.none')) }
          ]}
        />
      </div>
      <div>
        <Typography.Text strong>{t('skeleton.apiBindings')}</Typography.Text>
        <Table
          size="small"
          pagination={false}
          rowKey={(record) => `${record.method}-${record.path}-${record.consumer}`}
          dataSource={apiBindings}
          columns={[
            { title: t('skeleton.method'), render: (_, record) => <Tag>{record.method}</Tag> },
            { title: t('skeleton.path'), dataIndex: 'path' },
            { title: t('skeleton.consumer'), dataIndex: 'consumer' }
          ]}
        />
      </div>
    </Space>
  );
}

function parseSkeleton(content: string): { ok: true; value: FrontendSkeletonArtifact } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(content) as FrontendSkeletonArtifact };
  } catch {
    return { ok: false };
  }
}

function renderTags(values: string[] | undefined, emptyText: string) {
  if (!values?.length) {
    return <Typography.Text className="muted">{emptyText}</Typography.Text>;
  }
  return (
    <Space size={[4, 4]} wrap>
      {values.map((value) => (
        <Tag key={value}>{value}</Tag>
      ))}
    </Space>
  );
}

export default FrontendSkeletonPreview;
