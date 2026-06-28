import { Empty, List, Space, Tag, Typography } from 'antd';
import type { AgentEventResponse } from '../api/projects';

interface ExecutionEventListProps {
  events: AgentEventResponse[];
}

function ExecutionEventList({ events }: ExecutionEventListProps) {
  return (
    <section className="panel">
      <div className="section-heading">
        <Typography.Title level={2}>Execution events</Typography.Title>
        <Typography.Text className="muted">{events.length} events</Typography.Text>
      </div>
      {events.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No events yet" />
      ) : (
        <List
          className="event-list"
          dataSource={events}
          renderItem={(event) => (
            <List.Item>
              <Space direction="vertical" size={4} className="full-width">
                <Space wrap>
                  <Tag color={event.eventType.includes('FAILED') ? 'red' : 'blue'}>{event.eventType}</Tag>
                  <Typography.Text strong>{event.nodeName}</Typography.Text>
                  {event.createdAt ? <Typography.Text className="muted">{event.createdAt}</Typography.Text> : null}
                </Space>
                <Typography.Text>{event.message ?? event.payload ?? 'Event recorded'}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </section>
  );
}

export default ExecutionEventList;
