import { Alert, Descriptions, Empty, List, Space, Table, Tag, Typography } from 'antd';

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
  const parsed = parseSkeleton(content);
  if (!parsed.ok) {
    return <Alert type="warning" showIcon message="Invalid frontend skeleton JSON" description={content} />;
  }

  const skeleton = parsed.value;
  const apiBindings = skeleton.api_bindings ?? skeleton.apiBindings ?? [];

  return (
    <Space direction="vertical" size={20} className="full-width skeleton-preview">
      <div>
        <Typography.Text strong>Routes</Typography.Text>
        {skeleton.routes?.length ? (
          <Table
            size="small"
            pagination={false}
            rowKey={(record) => `${record.path}-${record.page}`}
            dataSource={skeleton.routes}
            columns={[
              { title: 'Path', dataIndex: 'path' },
              { title: 'Page', dataIndex: 'page' }
            ]}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No routes" />
        )}
      </div>
      <div>
        <Typography.Text strong>Pages</Typography.Text>
        <List
          dataSource={skeleton.pages ?? []}
          locale={{ emptyText: 'No pages' }}
          renderItem={(page) => (
            <List.Item>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="Name">{page.name}</Descriptions.Item>
                <Descriptions.Item label="Purpose">{page.purpose}</Descriptions.Item>
                <Descriptions.Item label="Components">{renderTags(page.components)}</Descriptions.Item>
              </Descriptions>
            </List.Item>
          )}
        />
      </div>
      <div>
        <Typography.Text strong>Components</Typography.Text>
        <Table
          size="small"
          pagination={false}
          rowKey={(record) => record.name ?? Math.random().toString(36)}
          dataSource={skeleton.components ?? []}
          columns={[
            { title: 'Name', dataIndex: 'name' },
            { title: 'Type', dataIndex: 'type' },
            { title: 'Props', render: (_, record) => renderTags(record.props) },
            { title: 'State', render: (_, record) => renderTags(record.state) }
          ]}
        />
      </div>
      <div>
        <Typography.Text strong>API bindings</Typography.Text>
        <Table
          size="small"
          pagination={false}
          rowKey={(record) => `${record.method}-${record.path}-${record.consumer}`}
          dataSource={apiBindings}
          columns={[
            { title: 'Method', render: (_, record) => <Tag>{record.method}</Tag> },
            { title: 'Path', dataIndex: 'path' },
            { title: 'Consumer', dataIndex: 'consumer' }
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

function renderTags(values?: string[]) {
  if (!values?.length) {
    return <Typography.Text className="muted">None</Typography.Text>;
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
