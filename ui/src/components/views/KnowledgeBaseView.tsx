import React, { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  Empty,
  Spin,
  Tag,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
  EyeOutlined,
  MessageOutlined,
} from "@ant-design/icons";
import type { TableColumnsType, UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { uploadDocument, type DocumentVO } from "../../api/api.ts";
import { IS_PUBLIC_DEMO } from "../../config.ts";
import DocumentPreviewDrawer from "../modals/DocumentPreviewDrawer.tsx";
import { showRequestError } from "../../api/http.ts";
import { notify } from "../../api/feedback.ts";

const { Title, Text, Paragraph } = Typography;
const isMarkdown = (filename: string) => /\.(?:md|markdown)$/i.test(filename);

const KnowledgeBaseContent: React.FC<{ knowledgeBaseId?: string }> = ({ knowledgeBaseId }) => {
  const navigate = useNavigate();
  const { knowledgeBases, loading: loadingKnowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);
  const [previewDocument, setPreviewDocument] = useState<DocumentVO | null>(null);

  // 查找当前知识库的详细信息
  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);
  const readOnly = IS_PUBLIC_DEMO || currentKnowledgeBase?.readOnly === true;

  // 处理文件上传
  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId || readOnly) {
      notify.error("请先选择知识库");
      return;
    }

    setUploading(true);

    try {
      await uploadDocument(knowledgeBaseId, file as File);
      notify.success("文档上传成功");
      await refreshDocuments();
      onSuccess?.(file);
    } catch (error) {
      showRequestError(error, "上传失败");
      onError?.(error as Error);
    } finally {
      setUploading(false);
    }
  };

  // 格式化文件大小
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  // 表格列定义
  const columns: TableColumnsType<DocumentVO> = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string, record: DocumentVO) => (
        <div className="flex min-w-0 items-start gap-2">
          <FileOutlined className="mt-1 shrink-0 text-slate-400" />
          {isMarkdown(text) ? (
            <button
              type="button"
              className="min-w-0 cursor-pointer break-all text-left text-indigo-600 hover:underline"
              aria-label={`预览 ${text}`}
              onClick={() => setPreviewDocument(record)}
            >
              {text}
            </button>
          ) : <span className="min-w-0 break-all">{text}</span>}
        </div>
      ),
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 100,
      responsive: ["sm"],
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 100,
      responsive: ["sm"],
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: readOnly ? 88 : 160,
      render: (_: unknown, record: DocumentVO) => (
        <Space size={0} wrap>
          <Button
            type="link"
            icon={<EyeOutlined />}
            size="small"
            disabled={!isMarkdown(record.filename)}
            onClick={() => setPreviewDocument(record)}
          >
            预览
          </Button>
          {!readOnly && !record.readOnly && <Popconfirm
            title="确定要删除这个文档吗？"
            description="删除后将无法恢复"
            onConfirm={() => deleteDocument(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="text" danger icon={<DeleteOutlined />} size="small">
              删除
            </Button>
          </Popconfirm>}
        </Space>
      ),
    },
  ];

  // 未选择知识库时的提示
  if (loadingKnowledgeBases) {
    return <div className="flex h-full items-center justify-center"><Spin /></div>;
  }

  if (IS_PUBLIC_DEMO && !knowledgeBaseId) {
    return (
      <div className="h-full overflow-y-auto bg-white p-5 sm:p-8">
        <div className="mx-auto max-w-4xl">
          <Tag color="geekblue">公开资料</Tag>
          <Title level={2} className="mt-3!">公开知识库</Title>
          <Paragraph type="secondary">浏览公开项目与技术资料，在聊天中检索并查看文档来源。</Paragraph>
          {knowledgeBases.length === 0 ? <Empty description="公开知识库准备中" /> : (
            <div className="mt-6 grid gap-4">
              {knowledgeBases.map((knowledgeBase) => (
                <Card key={knowledgeBase.knowledgeBaseId}>
                  <div className="flex items-start gap-4">
                    <BookOutlined className="mt-1 text-2xl text-indigo-500" />
                    <div className="min-w-0 flex-1">
                      <Title level={4}>{knowledgeBase.name}</Title>
                      <Paragraph type="secondary">{knowledgeBase.description}</Paragraph>
                      <Button onClick={() => navigate(`/knowledge-base/${knowledgeBase.knowledgeBaseId}`)}>查看文档列表</Button>
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<BookOutlined className="text-6xl text-gray-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择知识库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 知识库不存在
  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                知识库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 显示知识库详情和文档列表
  return (
    <div className="flex min-w-0 flex-col h-full p-5 sm:p-8 overflow-y-auto">
      <div className="min-w-0 max-w-6xl w-full mx-auto">
        <div className="mb-3">
          <Card>
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center text-3xl shrink-0">
                <BookOutlined />
              </div>
              <div className="min-w-0 flex-1">
                <Title level={3} className="mb-2">
                  {currentKnowledgeBase.name}
                </Title>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-gray-600 mb-0">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                {readOnly ? <Tag color="geekblue">公开资料 · 可阅读与检索</Tag> : <Text type="secondary" className="text-sm">
                  知识库 ID: {currentKnowledgeBase.knowledgeBaseId}
                </Text>}
              </div>
            </div>
          </Card>
        </div>
        {/* 知识库信息卡片 */}

        {readOnly && (
          <div className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
            <span className="flex-1 text-sm leading-6 text-slate-600">点击文件名阅读原文，也可以在对话中检索资料与来源。</span>
            <Button type="primary" icon={<MessageOutlined />} onClick={() => navigate("/chat", {
              state: { suggestedPrompt: `请检索公开知识库「${currentKnowledgeBase.name}」，介绍 ExoBrain 的技术架构与检索流程，并注明文档来源。` },
            })}>用这个知识库提问</Button>
          </div>
        )}

        {!readOnly && <div className="mb-3">
          {/* 上传文档区域 */}
          <Card title="上传文档">
            <Upload
              customRequest={handleUpload}
              showUploadList={false}
              accept=".md,.markdown"
              disabled={uploading}
            >
              <Button
                type="primary"
                icon={<UploadOutlined />}
                loading={uploading}
                size="large"
              >
                选择文件上传
              </Button>
            </Upload>
            <Text type="secondary" className="block mt-2 text-xs">
              支持格式: Markdown
            </Text>
          </Card>
        </div>}

        <div className="mb-3">
          {/* 文档列表 */}
          <Card title={`文档列表 (${documents.length})`}>
            {loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={<Text type="secondary">{readOnly ? "暂无公开文档" : "暂无文档，请上传文档"}</Text>}
              />
            ) : (
              <Table
                columns={columns}
                dataSource={documents}
                rowKey="id"
                tableLayout="fixed"
                pagination={{
                  pageSize: 10,
                  // showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>
      </div>
      {previewDocument && (
        <DocumentPreviewDrawer
          key={previewDocument.id}
          document={previewDocument}
          onClose={() => setPreviewDocument(null)}
        />
      )}
    </div>
  );
};

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  // Remount knowledge-base state so pending lists and previews cannot cross routes.
  return <KnowledgeBaseContent key={knowledgeBaseId ?? "index"} knowledgeBaseId={knowledgeBaseId} />;
};

export default KnowledgeBaseView;
