package com.mall.agent.tools;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 生成知识库里的 docx / pdf 样本文档(md 文档是手写的)。生成结果已提交进仓库,不需要每次运行。
 * 运行: java -cp "target/test-classes;<依赖classpath>" com.mall.agent.tools.GenerateKbDocs src/main/resources/kb
 * PDF 里的中文需要本机字体,默认用 Windows 的 simhei.ttf,可通过第二个参数指定其他 ttf。
 */
public class GenerateKbDocs {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/resources/kb");
        String font = args.length > 1 ? args[1] : "C:/Windows/Fonts/simhei.ttf";
        returnPolicyV2(root.resolve("售后/return-policy__v2.docx"));
        shippingFee(root.resolve("物流/shipping-fee__v1.docx"));
        sizeGuide(root.resolve("选购指南/size-guide__v1.docx"));
        promoPdf(root.resolve("活动规则/promo-2026__v1.pdf"), font);
        System.out.println("generated under " + root.toAbsolutePath());
    }

    // ---------- docx ----------

    private static void returnPolicyV2(Path out) throws Exception {
        try (XWPFDocument d = new XWPFDocument()) {
            heading(d, 1, "退换货政策(2026年修订版)");
            heading(d, 2, "七日无理由退货");
            para(d, "自商品签收之日起7天内,商品完好、不影响二次销售的,可申请七日无理由退货。钻石会员的退货期限延长至15天。");
            heading(d, 3, "例外情况");
            para(d, "以下商品不支持七日无理由退货:");
            para(d, "1. 定制类商品,例如刻字、专属定制商品;");
            para(d, "2. 贴身类商品,例如内衣裤、袜子、泳衣;");
            para(d, "3. 已拆封的食品、美妆护肤品和入耳式耳机(出于卫生和安全原因);");
            para(d, "4. 鲜活易腐商品和虚拟商品(充值卡、电子券)。");
            heading(d, 2, "退款时效");
            para(d, "退货商品签收并验收通过后,按下表时效原路退回:");
            table(d, List.of(
                    List.of("支付方式", "退款到账时间"),
                    List.of("支付宝/微信", "1-3个工作日"),
                    List.of("银行卡(借记卡)", "3-7个工作日"),
                    List.of("信用卡", "5-10个工作日"),
                    List.of("平台余额", "即时到账")));
            heading(d, 2, "换货");
            para(d, "商品存在质量问题的,自签收之日起15天内可申请换货,换货运费由商家承担。换货商品库存不足时,将直接为您办理退款。");
            heading(d, 2, "运费说明");
            para(d, "无理由退货的运费由买家承担;商品存在质量问题的,运费由商家承担。钻石会员每月可享受3次免费退货运费。");
            write(d, out);
        }
    }

    private static void shippingFee(Path out) throws Exception {
        try (XWPFDocument d = new XWPFDocument()) {
            heading(d, 1, "运费与配送时效");
            heading(d, 2, "运费模板");
            para(d, "运费按收货地区和重量计算,首重1公斤,不足1公斤按1公斤计。");
            table(d, List.of(
                    List.of("收货地区", "首重运费", "续重运费(每公斤)", "预计时效"),
                    List.of("华东(沪苏浙皖)", "6元", "2元", "1-2天"),
                    List.of("华北/华中/华南", "8元", "3元", "2-3天"),
                    List.of("西南", "10元", "4元", "3-4天"),
                    List.of("西北/东北", "12元", "5元", "3-5天")));
            heading(d, 2, "包邮规则");
            para(d, "单笔订单实付金额满99元包邮;金卡、钻石会员全场包邮。偏远地区不参与满额包邮。");
            heading(d, 2, "偏远地区");
            para(d, "新疆、西藏、青海及内蒙古部分地区属于偏远地区,在标准运费基础上加收10元,时效顺延2-3天。");
            write(d, out);
        }
    }

    private static void sizeGuide(Path out) throws Exception {
        try (XWPFDocument d = new XWPFDocument()) {
            heading(d, 1, "尺码对照指南");
            heading(d, 2, "上衣尺码对照");
            para(d, "请根据身高体重选择,偏好宽松可选大一码。");
            table(d, List.of(
                    List.of("尺码", "身高(cm)", "体重(kg)", "胸围(cm)"),
                    List.of("S", "160-165", "45-55", "84-88"),
                    List.of("M", "165-170", "55-65", "88-94"),
                    List.of("L", "170-175", "65-75", "94-100"),
                    List.of("XL", "175-180", "75-85", "100-106"),
                    List.of("XXL", "180-185", "85-95", "106-112")));
            heading(d, 2, "鞋码对照");
            para(d, "脚长为站立时脚后跟到最长脚趾的距离,脚背较高或脚宽的用户建议选大半码。");
            table(d, List.of(
                    List.of("脚长(mm)", "鞋码(欧码)"),
                    List.of("240", "38"),
                    List.of("245", "39"),
                    List.of("250", "40"),
                    List.of("255", "41"),
                    List.of("260", "42"),
                    List.of("265", "43")));
            write(d, out);
        }
    }

    private static void heading(XWPFDocument d, int level, String text) {
        XWPFParagraph p = d.createParagraph();
        p.setStyle("Heading" + level);
        p.createRun().setText(text);
    }

    private static void para(XWPFDocument d, String text) {
        d.createParagraph().createRun().setText(text);
    }

    private static void table(XWPFDocument d, List<List<String>> rows) {
        XWPFTable t = d.createTable(rows.size(), rows.get(0).size());
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                t.getRow(r).getCell(c).setText(rows.get(r).get(c));
            }
        }
    }

    private static void write(XWPFDocument d, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out)) {
            d.write(os);
        }
    }

    // ---------- pdf ----------

    private static void promoPdf(Path out, String fontFile) throws Exception {
        List<String> lines = List.of(
                "2026年双11大促活动规则",
                "",
                "一、活动时间",
                "11月1日0点至11月11日24点。",
                "",
                "二、预售规则",
                "预售商品需先支付定金,尾款支付时间以商品页面为准。",
                "付完尾款后72小时内发货。",
                "定金不予退还,因商家原因导致无法发货的除外。",
                "",
                "三、满减优惠",
                "活动期间跨店每满300元减40元,每笔订单最高减400元。",
                "满减可与1张平台券叠加,不可与店铺折扣券同时使用。",
                "",
                "四、价保规则",
                "活动期间购买的商品,自下单之日起15天内降价,可申请补差价。",
                "",
                "五、售后说明",
                "活动商品的退换货规则以退换货政策为准。",
                "秒杀商品不支持使用优惠券。");
        Files.createDirectories(out.getParent());
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, new File(fontFile));
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.setLeading(20);
                cs.newLineAtOffset(60, 740);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out.toFile());
        }
    }
}
