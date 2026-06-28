import { Empty, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ReviewIssueResponse, ReviewResponse } from '../api/projects';

interface ReviewIssueTableProps {
  review: ReviewResponse | null;
}

const columns: ColumnsType<ReviewIssueResponse> = [
  {
    title: 'Severity',
    dataIndex: 'severity',
    key: 'severity',
    width: 120,
    render: (value: string) => <Tag color={tagColor(value)}>{value}</Tag>
  },
  {
    title: 'Type',
    dataIndex: 'issueType',
    key: 'issueType',
    width: 180
  },
  {
    title: 'Description',
    dataIndex: 'description',
    key: 'description'
  },
  {
    title: 'Suggestion',
    dataIndex: 'suggestion',
    key: 'suggestion'
  },
  {
    title: 'Status',
    dataIndex: 'status',
    key: 'status',
    width: 120
  }
];

function ReviewIssueTable({ review }: ReviewIssueTableProps) {
  return (
    <section className="panel">
      <div className="section-heading">
        <Typography.Title level={2}>Review issues</Typography.Title>
        <Typography.Text className="score">{review ? `${review.score}/100` : '--'}</Typography.Text>
      </div>
      {review && review.issues.length > 0 ? (
        <Table
          rowKey={(record) => `${record.issueType}-${record.description}`}
          columns={columns}
          dataSource={review.issues}
          pagination={false}
          scroll={{ x: 760 }}
        />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No review issues" />
      )}
    </section>
  );
}

function tagColor(severity: string) {
  if (severity === 'HIGH') {
    return 'red';
  }
  if (severity === 'MEDIUM') {
    return 'orange';
  }
  if (severity === 'LOW') {
    return 'blue';
  }
  return 'default';
}

export default ReviewIssueTable;
