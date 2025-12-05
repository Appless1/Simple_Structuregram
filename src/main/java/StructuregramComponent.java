import com.intellij.psi.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;


public class StructuregramComponent extends JPanel {

    private final NsdBlock rootBlock;
    private static final int PADDING = 20;
    private static final int LINE_HEIGHT = 20; // Base height per line of text
    private static final int BLOCK_PADDING = 10; // Padding inside a block
    private static final int MIN_BLOCK_WIDTH = 250;
    private static final Font FONT_BOLD = new Font("SansSerif", Font.BOLD, 12);
    private static final Font FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 12);

    // Zooming state
    private double scaleFactor = 1.0;
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 5.0;

    // Panning state
    private double translateX = 0;
    private double translateY = 0;
    private Point lastDragPoint;

    public StructuregramComponent(PsiMethod method) {
        this.rootBlock = parseMethod(method);
        setBackground(Color.WHITE);
        setFocusable(true); // Ensure we can receive inputs if needed

        // Mouse Adapter for Panning (Middle Click) and Zooming
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    if (e.getWheelRotation() < 0) {
                        scaleFactor *= 1.1; // Zoom In
                    } else {
                        scaleFactor /= 1.1; // Zoom Out
                    }
                    // Clamp scale
                    scaleFactor = Math.max(MIN_SCALE, Math.min(scaleFactor, MAX_SCALE));

                    revalidate();
                    repaint();
                    e.consume();
                } else {
                    // Propagate to parent scroll pane if control is not held
                    getParent().dispatchEvent(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    lastDragPoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    lastDragPoint = null;
                    setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint != null && SwingUtilities.isMiddleMouseButton(e)) {
                    double dx = e.getX() - lastDragPoint.getX();
                    double dy = e.getY() - lastDragPoint.getY();

                    translateX += dx;
                    translateY += dy;

                    lastDragPoint = e.getPoint();
                    repaint();
                }
            }
        };

        addMouseWheelListener(mouseAdapter);
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
    }


    private NsdBlock parseMethod(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return new SimpleBlock("Abstract/Native Method");
        return parseCodeBlock(body);
    }

    private NsdBlock parseCodeBlock(PsiCodeBlock block) {
        ContainerBlock container = new ContainerBlock();
        for (PsiStatement statement : block.getStatements()) {
            container.add(parseStatement(statement));
        }
        return container;
    }

    private NsdBlock parseStatement(PsiStatement stmt) {
        if (stmt instanceof PsiIfStatement) {
            PsiIfStatement ifStmt = (PsiIfStatement) stmt;
            String condition = ifStmt.getCondition() != null ? ifStmt.getCondition().getText() : "?";

            condition = naturalize(condition) + "?";

            NsdBlock thenBlock = parseBody(ifStmt.getThenBranch());
            NsdBlock elseBlock = parseBody(ifStmt.getElseBranch());

            return new IfBlock(condition, thenBlock, elseBlock);
        }
        else if (stmt instanceof PsiSwitchStatement) {
            PsiSwitchStatement switchStmt = (PsiSwitchStatement) stmt;
            String expression = switchStmt.getExpression() != null ? switchStmt.getExpression().getText() : "?";

            List<SwitchBlock.CaseInfo> cases = new ArrayList<>();
            PsiCodeBlock body = switchStmt.getBody();

            if (body != null) {
                String currentLabel = null;
                ContainerBlock currentContainer = null;

                for (PsiStatement s : body.getStatements()) {
                    if (s instanceof PsiSwitchLabelStatement) {
                        // Finish previous case
                        if (currentLabel != null && currentContainer != null) {
                            cases.add(new SwitchBlock.CaseInfo(currentLabel, currentContainer));
                        }

                        // Start new case
                        currentContainer = new ContainerBlock();
                        PsiSwitchLabelStatement label = (PsiSwitchLabelStatement) s;
                        if (label.isDefaultCase()) {
                            currentLabel = "Default";
                        } else {
                            PsiCaseLabelElementList list = label.getCaseLabelElementList();
                            currentLabel = (list != null) ? naturalize(list.getText()) : "Case";
                        }
                    } else {
                        // Add statement to current case
                        if (currentContainer != null) {
                            currentContainer.add(parseStatement(s));
                        } else if (currentLabel == null) {
                            currentLabel = "Start";
                            currentContainer = new ContainerBlock();
                            currentContainer.add(parseStatement(s));
                        }
                    }
                }
                // Add the last case
                if (currentLabel != null && currentContainer != null) {
                    cases.add(new SwitchBlock.CaseInfo(currentLabel, currentContainer));
                }
            }
            return new SwitchBlock(naturalize(expression), cases);
        }
        else if (stmt instanceof PsiWhileStatement) {
            PsiWhileStatement loop = (PsiWhileStatement) stmt;
            String condition = loop.getCondition() != null ? loop.getCondition().getText() : "true";
            return new LoopBlock("While " + naturalize(condition), parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiDoWhileStatement) {
            PsiDoWhileStatement loop = (PsiDoWhileStatement) stmt;
            String condition = loop.getCondition() != null ? loop.getCondition().getText() : "true";
            return new LoopBlock("Do ... Until " + naturalize(condition), parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiForStatement) {
            PsiForStatement loop = (PsiForStatement) stmt;
            String text = "Loop";
            PsiStatement init = loop.getInitialization();
            PsiExpression cond = loop.getCondition();

            if (init != null && cond != null) {
                text = "For " + naturalize(init.getText()) + " to " + naturalize(cond.getText());
            }
            return new LoopBlock(text, parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiForeachStatement) {
            PsiForeachStatement loop = (PsiForeachStatement) stmt;
            String param = loop.getIterationParameter().getName();
            String value = loop.getIteratedValue() != null ? loop.getIteratedValue().getText() : "?";
            return new LoopBlock("For each " + param + " in " + value, parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiBlockStatement) {
            return parseCodeBlock(((PsiBlockStatement) stmt).getCodeBlock());
        }
        else if (stmt instanceof PsiReturnStatement) {
            String retVal = ((PsiReturnStatement)stmt).getReturnValue() != null ?
                    ((PsiReturnStatement)stmt).getReturnValue().getText() : "";
            return new SimpleBlock("Return " + naturalize(retVal));
        }
        else if (stmt instanceof PsiBreakStatement) {
            return new SimpleBlock("Break");
        }
        else if (stmt instanceof PsiContinueStatement) {
            return new SimpleBlock("Continue");
        }
        else if (stmt instanceof PsiDeclarationStatement) {
            PsiDeclarationStatement decl = (PsiDeclarationStatement) stmt;
            StringBuilder sb = new StringBuilder();
            for (PsiElement el : decl.getDeclaredElements()) {
                if (el instanceof PsiLocalVariable) {
                    PsiLocalVariable var = (PsiLocalVariable) el;
                    sb.append(var.getName());
                    if (var.getInitializer() != null) {
                        sb.append(" := ").append(naturalize(var.getInitializer().getText()));
                    }
                }
            }
            if (sb.length() == 0) return new SimpleBlock(naturalize(stmt.getText()));
            return new SimpleBlock(sb.toString());
        }

        return new SimpleBlock(naturalize(stmt.getText()));
    }

    private NsdBlock parseBody(PsiStatement body) {
        if (body == null) return new SimpleBlock("");
        if (body instanceof PsiBlockStatement) {
            return parseCodeBlock(((PsiBlockStatement) body).getCodeBlock());
        }
        return parseStatement(body);
    }

    private String naturalize(String code) {
        if (code == null) return "";
        String text = code.trim();

        if (text.endsWith(";")) text = text.substring(0, text.length() - 1);

        if (text.startsWith("System.out.print")) {
            int start = text.indexOf("(");
            int end = text.lastIndexOf(")");
            if (start > -1 && end > start) {
                return "Print " + text.substring(start+1, end);
            }
        }

        text = text.replace("&&", " and ");
        text = text.replace("||", " or ");
        text = text.replace("!", "not ");
        text = text.replace("this.", "");

        if (text.contains("=") && !text.contains("==") && !text.contains(">=") && !text.contains("<=") && !text.contains("!=")) {
            text = text.replace("=", ":=");
        }
        text = text.replace(" = ", " := ");

        // Remove types for simpler reading
        text = text.replaceAll("\\bint\\b", "").replaceAll("\\bString\\b", "")
                .replaceAll("\\bboolean\\b", "").replaceAll("\\bdouble\\b", "")
                .replaceAll("\\bfloat\\b", "").replaceAll("\\bvar\\b", "")
                .replaceAll("\\bchar\\b", "");

        return text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> wrapText(Graphics2D g, String text, int maxWidth, Font font) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        FontMetrics fm = g.getFontMetrics(font);
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String potential = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (fm.stringWidth(potential) <= maxWidth) {
                currentLine.append(currentLine.length() == 0 ? "" : " ").append(word);
            } else {
                if (currentLine.length() > 0) lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) lines.add(currentLine.toString());
        return lines;
    }


    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // Setup rendering
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.2f));

        if (rootBlock != null) {
            // 1. Calculate Logical (Unscaled) Size first
            Dimension logicalDim = rootBlock.calculateSize(g2);

            // 2. Update Preferred Size based on Scale
            int scaledW = (int) ((logicalDim.width + PADDING * 2) * scaleFactor);
            int scaledH = (int) ((logicalDim.height + PADDING * 2) * scaleFactor);

            Dimension currentPref = getPreferredSize();
            if (currentPref.width != scaledW || currentPref.height != scaledH) {
                setPreferredSize(new Dimension(scaledW, scaledH));
                revalidate();
            }

            // 3. Apply Transformations (Pan then Zoom)
            AffineTransform oldTransform = g2.getTransform();

            g2.translate(translateX, translateY); // Apply Pan
            g2.scale(scaleFactor, scaleFactor);   // Apply Zoom
            g2.translate(PADDING, PADDING);       // Apply Base Padding

            // 4. Draw
            g2.setColor(Color.BLACK);
            g2.drawRect(0, 0, logicalDim.width, logicalDim.height);
            rootBlock.draw(g2, 0, 0, logicalDim.width);

            // 5. Restore Transform
            g2.setTransform(oldTransform);
        }
    }

    private static void drawMultilineString(Graphics2D g, List<String> lines, int x, int y, int w, int h) {
        if (lines.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getHeight();
        int totalTextHeight = lines.size() * lineHeight;

        int startY = y + (h - totalTextHeight) / 2 + fm.getAscent();

        for (String line : lines) {
            int textX = x + (w - fm.stringWidth(line)) / 2;
            g.drawString(line, textX, startY);
            startY += lineHeight;
        }
    }


    interface NsdBlock {
        Dimension calculateSize(Graphics2D g);
        int draw(Graphics2D g, int x, int y, int width);
    }

    static class ContainerBlock implements NsdBlock {
        List<NsdBlock> children = new ArrayList<>();
        void add(NsdBlock block) { children.add(block); }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            int w = MIN_BLOCK_WIDTH;
            int h = 0;
            for (NsdBlock b : children) {
                Dimension d = b.calculateSize(g);
                w = Math.max(w, d.width);
                h += d.height;
            }
            return new Dimension(w, Math.max(h, 20));
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int currentY = y;
            for (NsdBlock b : children) {
                int h = b.draw(g, x, currentY, width);
                currentY += h;
            }
            return currentY - y;
        }
    }

    static class SimpleBlock implements NsdBlock {
        String text;
        SimpleBlock(String text) { this.text = text; }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            int textWidth = g.getFontMetrics(FONT_PLAIN).stringWidth(text);
            int width = Math.max(MIN_BLOCK_WIDTH, Math.min(textWidth + 20, 500));
            List<String> lines = wrapText(g, text, width - 20, FONT_PLAIN);
            int height = Math.max(LINE_HEIGHT, lines.size() * (g.getFontMetrics(FONT_PLAIN).getHeight() + 2) + BLOCK_PADDING);
            return new Dimension(width, height);
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            Dimension size = calculateSize(g);
            int height = size.height;

            g.setColor(Color.BLACK);
            g.drawRect(x, y, width, height);
            g.setFont(FONT_PLAIN);

            List<String> lines = wrapText(g, text, width - 20, FONT_PLAIN);
            drawMultilineString(g, lines, x, y, width, height);

            return height;
        }
    }

    static class IfBlock implements NsdBlock {
        String condition;
        NsdBlock thenBlock, elseBlock;

        IfBlock(String condition, NsdBlock thenBlock, NsdBlock elseBlock) {
            this.condition = condition;
            this.thenBlock = thenBlock;
            this.elseBlock = elseBlock;
        }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            Dimension t = thenBlock.calculateSize(g);
            Dimension e = elseBlock.calculateSize(g);
            int headerH = (int)(LINE_HEIGHT * 2.5);
            return new Dimension(Math.max(t.width + e.width, MIN_BLOCK_WIDTH), Math.max(t.height, e.height) + headerH);
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int headerH = (int)(LINE_HEIGHT * 2.5);
            int thenH = thenBlock.calculateSize(g).height;
            int elseH = elseBlock.calculateSize(g).height;
            int contentH = Math.max(thenH, elseH);

            g.setColor(Color.BLACK);
            g.drawRect(x, y, width, headerH);

            int midX = x + (width / 2);
            int bottomY = y + headerH;

            g.drawLine(x, y, midX, bottomY);
            g.drawLine(x + width, y, midX, bottomY);

            g.setFont(FONT_BOLD);
            drawMultilineString(g, wrapText(g, condition, width/2, FONT_BOLD), x, y, width, headerH/2 + 5);

            g.setFont(FONT_PLAIN);
            g.drawString("True", x + 5, bottomY - 5);
            g.drawString("False", x + width - 35, bottomY - 5);

            thenBlock.draw(g, x, bottomY, width / 2);
            elseBlock.draw(g, midX, bottomY, width / 2);

            g.drawRect(x, bottomY, width / 2, contentH);
            g.drawRect(midX, bottomY, width / 2, contentH);

            return headerH + contentH;
        }
    }

    static class LoopBlock implements NsdBlock {
        String condition;
        NsdBlock body;

        LoopBlock(String condition, NsdBlock body) {
            this.condition = condition;
            this.body = body;
        }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            Dimension b = body.calculateSize(g);
            return new Dimension(b.width + 40, b.height + LINE_HEIGHT + 10);
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int barWidth = 30;
            int headerHeight = LINE_HEIGHT + 10;
            int bodyHeight = body.calculateSize(g).height;
            int totalHeight = bodyHeight + headerHeight;

            g.setColor(Color.BLACK);
            g.drawRect(x, y, width, headerHeight);
            g.setFont(FONT_BOLD);
            drawMultilineString(g, wrapText(g, condition, width - 10, FONT_BOLD), x, y, width, headerHeight);

            g.drawRect(x, y + headerHeight, barWidth, bodyHeight);
            body.draw(g, x + barWidth, y + headerHeight, width - barWidth);

            return totalHeight;
        }
    }

    static class SwitchBlock implements NsdBlock {
        static class CaseInfo {
            String label;
            NsdBlock block;
            CaseInfo(String label, NsdBlock block) { this.label = label; this.block = block; }
        }

        String expression;
        List<CaseInfo> cases;

        SwitchBlock(String expression, List<CaseInfo> cases) {
            this.expression = expression;
            this.cases = cases;
        }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            int totalW = 0;
            int maxH = 0;
            for (CaseInfo c : cases) {
                Dimension d = c.block.calculateSize(g);
                totalW += d.width;
                maxH = Math.max(maxH, d.height);
            }
            int headerH = (int)(LINE_HEIGHT * 2.5);
            return new Dimension(Math.max(totalW, MIN_BLOCK_WIDTH), maxH + headerH);
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int headerH = (int)(LINE_HEIGHT * 2.5);
            int contentTop = y + headerH;

            // Draw Header (Switch Condition)
            g.setColor(Color.BLACK);
            g.drawRect(x, y, width, headerH);
            g.setFont(FONT_BOLD);
            drawMultilineString(g, wrapText(g, "Switch: " + expression, width, FONT_BOLD), x, y + 5, width, headerH/2);

            if (cases.isEmpty()) return headerH;

            int currentX = x;
            int remainingWidth = width;
            int totalRequiredWidth = 0;
            for(CaseInfo c : cases) totalRequiredWidth += c.block.calculateSize(g).width;

            int maxContentH = 0;
            for(CaseInfo c : cases) maxContentH = Math.max(maxContentH, c.block.calculateSize(g).height);

            // Draw each case column
            for (int i = 0; i < cases.size(); i++) {
                CaseInfo c = cases.get(i);

                // Proportional width allocation
                int colWidth;
                if (i == cases.size() - 1) {
                    colWidth = remainingWidth; // Last one takes remainder to avoid gaps
                } else {
                    double ratio = (double) c.block.calculateSize(g).width / totalRequiredWidth;
                    colWidth = (int) (width * ratio);
                    if (colWidth < 50) colWidth = 50; // Min width safety
                }
                remainingWidth -= colWidth;

                // Draw Label Area (Diagonal split is complex, using sub-header)
                g.drawRect(currentX, contentTop - (headerH/2), colWidth, headerH/2);
                g.setFont(FONT_PLAIN);
                g.drawString(c.label, currentX + 5, contentTop - 5);

                // Draw Case Block
                c.block.draw(g, currentX, contentTop, colWidth);

                // Draw outline for case column to ensure full height matching
                g.drawRect(currentX, contentTop, colWidth, maxContentH);

                currentX += colWidth;
            }

            return headerH + maxContentH;
        }
    }
}