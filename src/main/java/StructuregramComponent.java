
import com.intellij.psi.*;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Updated Renderer with "Natural Language" processing.
 * - Converts Java syntax (==, &&, ;) into readable text (=, and).
 * - Uses classic symbols like assignment (:=).
 * - Simplifies complex statements (System.out.println -> Print).
 * - Implements text wrapping to ensure content fits.
 * - Supports Switch/Case diagrams with Fan-out style.
 */
public class StructuregramComponent extends JPanel {

    private final NsdBlock rootBlock;
    private static final int PADDING = 20;
    private static final int LINE_HEIGHT = 20; // Base height per line of text
    private static final int BLOCK_PADDING = 10; // Padding inside a block
    private static final int MIN_BLOCK_WIDTH = 250;
    private static final Font FONT_BOLD = new Font("SansSerif", Font.BOLD, 12);
    private static final Font FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 12);

    public StructuregramComponent(PsiMethod method) {
        this.rootBlock = parseMethod(method);
        setBackground(Color.WHITE);
        setToolTipText("Structuregram for " + method.getName());
    }

    // --- PARSING LOGIC ---

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

            SwitchBlock switchBlock = new SwitchBlock(naturalize(expression));

            PsiCodeBlock body = switchStmt.getBody();
            if (body != null) {
                ContainerBlock currentBody = null;
                String currentLabel = "";

                for (PsiStatement s : body.getStatements()) {
                    if (s instanceof PsiSwitchLabelStatement) {
                        if (currentBody != null) {
                            switchBlock.addCase(currentLabel, currentBody);
                        }
                        PsiSwitchLabelStatement label = (PsiSwitchLabelStatement) s;
                        if (label.isDefaultCase()) {
                            currentLabel = "Default";
                        } else {
                            PsiExpressionList values = label.getCaseValues();
                            if (values != null) {
                                currentLabel = naturalize(values.getText());
                            } else {
                                currentLabel = "?";
                            }
                        }
                        currentBody = new ContainerBlock();
                    } else {
                        if (currentBody != null) {
                            if (!(s instanceof PsiBreakStatement)) {
                                currentBody.add(parseStatement(s));
                            }
                        }
                    }
                }
                if (currentBody != null) {
                    switchBlock.addCase(currentLabel, currentBody);
                }
            }
            return switchBlock;
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
                        // Use := for declaration assignment
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

    // --- NATURAL LANGUAGE PROCESSOR ---
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

        // Assignments: Use :=
        // Ensure we don't accidentally replace ==, >=, <= which we want to keep or convert to symbols
        // First protect inequalities if needed, or just be careful with replacement

        // Convert strict equalities to symbols if desired, or keep as is.
        // The user said "if condition could stay as equals...".
        // But for assignment they want :=

        // If the string has " = " and NOT " == ", replace with :=
        // We can do a regex lookaround or simpler checks
        if (text.contains("=") && !text.contains("==") && !text.contains(">=") && !text.contains("<=") && !text.contains("!=")) {
            text = text.replace("=", ":=");
        }
        // Handle " = " specifically to be safe
        text = text.replace(" = ", " := ");

        // Remove types
        text = text.replaceAll("\\bint\\b", "");
        text = text.replaceAll("\\bString\\b", "");
        text = text.replaceAll("\\bboolean\\b", "");
        text = text.replaceAll("\\bdouble\\b", "");
        text = text.replaceAll("\\bfloat\\b", "");
        text = text.replaceAll("\\bvar\\b", "");
        text = text.replaceAll("\\bchar\\b", "");

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

    // --- DRAWING LOGIC ---

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.2f));

        if (rootBlock != null) {
            Dimension dim = rootBlock.calculateSize(g2);
            setPreferredSize(new Dimension(dim.width + PADDING * 2, dim.height + PADDING * 2));
            revalidate();

            g2.translate(PADDING, PADDING);
            g2.setColor(Color.BLACK);
            g2.drawRect(0, 0, dim.width, dim.height);
            rootBlock.draw(g2, 0, 0, dim.width);
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

    // --- DATA STRUCTURES ---

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

    static class SwitchBlock implements NsdBlock {
        String expression;
        List<String> labels = new ArrayList<>();
        List<NsdBlock> bodies = new ArrayList<>();

        SwitchBlock(String expression) {
            this.expression = expression;
        }

        void addCase(String label, NsdBlock body) {
            labels.add(label);
            bodies.add(body);
        }

        @Override
        public Dimension calculateSize(Graphics2D g) {
            int totalWidth = 0;
            int maxBodyHeight = 0;
            for (NsdBlock b : bodies) {
                Dimension d = b.calculateSize(g);
                totalWidth += d.width;
                maxBodyHeight = Math.max(maxBodyHeight, d.height);
            }
            int headerH = (int)(LINE_HEIGHT * 2.5);
            return new Dimension(Math.max(totalWidth, MIN_BLOCK_WIDTH), maxBodyHeight + headerH);
        }

        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            if (bodies.isEmpty()) return 0;

            int headerH = (int)(LINE_HEIGHT * 2.5);
            int maxBodyH = 0;
            int[] widths = new int[bodies.size()];
            int totalCalcWidth = 0;

            // 1. Calculate dimensions
            for (int i = 0; i < bodies.size(); i++) {
                Dimension d = bodies.get(i).calculateSize(g);
                widths[i] = d.width;
                totalCalcWidth += d.width;
                maxBodyH = Math.max(maxBodyH, d.height);
            }

            g.setColor(Color.BLACK);
            // Outer box
            g.drawRect(x, y, width, headerH + maxBodyH);
            // Header line
            g.drawLine(x, y + headerH, x + width, y + headerH);

            // 2. Draw Header Text
            g.setFont(FONT_BOLD);
            drawMultilineString(g, wrapText(g, expression, width/2, FONT_BOLD), x, y, width, headerH - 10);

            // 3. Draw Columns and Fan Lines
            int currentX = x;
            double scale = (double)width / totalCalcWidth;
            int bottomY = y + headerH;
            int midX = x + (width / 2);
            int midY = y + headerH; // Point where lines converge?
            // Actually, in the fan style, lines go from (MidX, BottomY of Header) to (Col Separators at Top)?
            // No, usually it's from (MidX, BottomY of Header) UP to corners, creating the "Variable" triangle.

            // Let's implement the style from the image:
            // A central triangle for the expression.
            // Lines radiate from Bottom-Center of the header area to the top-corners of the columns.

            g.setFont(FONT_PLAIN);

            for (int i = 0; i < bodies.size(); i++) {
                int colWidth = (int)(widths[i] * scale);
                if (i == bodies.size() - 1) colWidth = (x + width) - currentX;

                // Draw Body
                bodies.get(i).draw(g, currentX, bottomY, colWidth);

                // Draw vertical separator between columns
                if (i > 0) {
                    g.drawLine(currentX, bottomY, currentX, bottomY + maxBodyH);

                    // Draw Fan Line: From center of header bottom to the top of this separator
                    g.drawLine(midX, bottomY, currentX, y);
                    // Wait, strictly speaking, the fan lines usually originate from the bottom center of the header
                    // and go to the top of the header.
                    // Actually, simpler visual:
                    // Draw line from (MidX, BottomY) to (CurrentX, Y) is nice, but might cross text.
                    // Let's draw from (MidX, BottomY) to (CurrentX, BottomY) is just the separator line top.
                    // The diagonal needs to go from (MidX, BottomY) to somewhere on the top edge?
                    // No, looking at the diagram, the "Triangle" for variable is inverted.
                    // Top edge is flat. Sides go down to the center point.
                    // So we draw line from (x, y) to (MidX, BottomY) ? No that cuts the first block.

                    // Let's use the "Radiating from Bottom Center" style.
                    // Center point: (x + width/2, y + headerH).
                    // Rays go to: (currentX, y) (Top edge of header).
                    g.drawLine(x + width/2, y + headerH, currentX, y);
                }

                // Draw Label in the wedge
                // Position: roughly in the middle of the wedge
                // Wedge center X = (currentX + colWidth/2 + x + width/2) / 2 ... approx
                int labelX = currentX + 5;
                int labelY = y + 15;
                // Optimization: Place label closer to the top-left of the column section
                g.drawString(labels.get(i), labelX, labelY);

                currentX += colWidth;
            }

            // Draw the two main diagonals for the outer triangle if needed,
            // but the loop above handles the internal rays.
            // We need the first and last diagonal if we want a fully enclosed triangle for the expression?
            // The loop draws separators.
            // Let's draw the main V for the expression:
            // Line from Top-Left to Bottom-Center
            g.drawLine(x, y, x + width/2, y + headerH);
            // Line from Top-Right to Bottom-Center
            g.drawLine(x + width, y, x + width/2, y + headerH);

            return headerH + maxBodyH;
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
            // Header box
            g.drawRect(x, y, width, headerHeight);
            g.setFont(FONT_BOLD);
            drawMultilineString(g, wrapText(g, condition, width - 10, FONT_BOLD), x, y, width, headerHeight);

            // Side bar (L shape)
            g.drawRect(x, y + headerHeight, barWidth, bodyHeight);

            // Body
            body.draw(g, x + barWidth, y + headerHeight, width - barWidth);

            return totalHeight;
        }
    }
}