-- 与用户本地已建表一致(加了 IF NOT EXISTS,便于测试用 H2 重复初始化)

CREATE TABLE IF NOT EXISTS product (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  name        VARCHAR(128)  NOT NULL COMMENT '商品名称',
  category    VARCHAR(32)   NOT NULL COMMENT '类目,如 手机/服饰/家电',
  brand       VARCHAR(64)   DEFAULT NULL COMMENT '品牌',
  price       DECIMAL(10,2) NOT NULL COMMENT '售价(元)',
  stock       INT           NOT NULL DEFAULT 0 COMMENT '库存数量',
  description VARCHAR(1024) DEFAULT NULL COMMENT '卖点简述',
  attrs       VARCHAR(1024) DEFAULT NULL COMMENT '扩展属性:颜色、尺码、规格等(JSON文本)',
  on_sale     TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否在售:1在售 0下架',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS orders (
  order_no     VARCHAR(32)   NOT NULL COMMENT '订单号',
  user_id      VARCHAR(64)   NOT NULL COMMENT '下单用户ID(查询时必须带它做归属校验,防越权)',
  status       VARCHAR(16)   NOT NULL COMMENT '订单状态:UNPAID/PAID/SHIPPED/DONE/REFUNDING/CLOSED',
  total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总金额(元)',
  receiver     VARCHAR(64)   DEFAULT NULL COMMENT '收货人',
  address      VARCHAR(256)  DEFAULT NULL COMMENT '收货地址',
  created_at   DATETIME      NOT NULL COMMENT '下单时间',
  paid_at      DATETIME      DEFAULT NULL COMMENT '支付时间',
  shipped_at   DATETIME      DEFAULT NULL COMMENT '发货时间',
  PRIMARY KEY (order_no)
);

CREATE TABLE IF NOT EXISTS order_item (
  id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no     VARCHAR(32)   NOT NULL COMMENT '所属订单号',
  product_id   BIGINT        NOT NULL COMMENT '商品ID',
  product_name VARCHAR(128)  NOT NULL COMMENT '商品名称(下单时快照)',
  price        DECIMAL(10,2) NOT NULL COMMENT '成交单价(元,下单时快照)',
  quantity     INT           NOT NULL COMMENT '购买数量',
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS user_preference (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     VARCHAR(64)   NOT NULL COMMENT '用户ID',
  pref_key    VARCHAR(32)   NOT NULL COMMENT '偏好类型:brand/budget/size/style/category',
  pref_value  VARCHAR(128)  NOT NULL COMMENT '偏好值',
  confidence  DECIMAL(3,2)  NOT NULL DEFAULT 0.50 COMMENT '置信度0~1',
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_key_val (user_id, pref_key, pref_value)
);

CREATE TABLE IF NOT EXISTS conversation_summary (
  session_id        VARCHAR(64) NOT NULL COMMENT '会话ID',
  user_id           VARCHAR(64) NOT NULL COMMENT '用户ID',
  summary           TEXT        NOT NULL COMMENT '早期历史压缩出的摘要',
  covered_msg_count INT         NOT NULL DEFAULT 0 COMMENT '摘要已覆盖的消息条数',
  updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (session_id)
);

CREATE TABLE IF NOT EXISTS kb_document (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_id       VARCHAR(64)  NOT NULL COMMENT '文档标识',
  version      INT          NOT NULL COMMENT '文档版本号',
  title        VARCHAR(128) NOT NULL COMMENT '文档标题',
  category     VARCHAR(32)  NOT NULL COMMENT '类别',
  file_name    VARCHAR(128) NOT NULL COMMENT '源文件名',
  content_hash CHAR(64)     NOT NULL COMMENT '文件内容SHA-256',
  chunk_count  INT          NOT NULL DEFAULT 0 COMMENT '切分块数量',
  effective    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否生效',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_version (doc_id, version)
);
