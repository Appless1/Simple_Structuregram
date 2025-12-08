import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class StructuregramComponent extends JPanel {

    public enum ThemeMode {
        AUTO, DARK, LIGHT
    }

    private final SmartPsiElementPointer<PsiMethod> methodPointer;
    private NsdBlock rootBlock;

    private Alarm updateAlarm;
    private Disposable connectionDisposable;
    private PsiTreeChangeListener psiListener;

    private static final int PADDING = 20;
    private static final int LINE_HEIGHT = 20;
    private static final int BLOCK_PADDING = 10;
    private static final int MIN_BLOCK_WIDTH = 250;

    private ThemeMode currentThemeMode = ThemeMode.AUTO;
    private Font fontPlain;
    private Font fontBold;

    private double scaleFactor = 1.0;
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 5.0;

    private double translateX = 0;
    private double translateY = 0;
    private Point lastDragPoint;

    public StructuregramComponent(PsiMethod method) {
        this.methodPointer = SmartPointerManager.getInstance(method.getProject())
                .createSmartPsiElementPointer(method);

        ApplicationManager.getApplication().runReadAction(() -> {
            this.rootBlock = parseMethod(method);
        });

        setOpaque(true);
        setFocusable(true);

        setupMouseInteractions();
        setupPsiListener();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (connectionDisposable == null) {
            connectionDisposable = Disposer.newDisposable("StructuregramConnection");
            updateAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, connectionDisposable);
            PsiMethod method = methodPointer.getElement();
            if (method != null && method.isValid()) {
                PsiManager.getInstance(method.getProject())
                        .addPsiTreeChangeListener(psiListener, connectionDisposable);
            }
        }
    }

    @Override
    public void removeNotify() {
        if (connectionDisposable != null) {
            Disposer.dispose(connectionDisposable);
            connectionDisposable = null;
            updateAlarm = null;
        }
        super.removeNotify();
    }

    private void setupPsiListener() {
        psiListener = new PsiTreeChangeAdapter() {
            @Override
            public void childrenChanged(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override
            public void childAdded(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override
            public void childRemoved(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override
            public void childReplaced(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override
            public void childMoved(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
        };
    }

    private void scheduleUpdate(PsiTreeChangeEvent event) {
        if (updateAlarm == null || updateAlarm.isDisposed()) return;
        PsiMethod method = methodPointer.getElement();
        if (method == null || !method.isValid()) return;
        PsiElement parent = event.getParent();
        if (parent == null) return;

        if (PsiTreeUtil.isAncestor(method, parent, false) || parent == method) {
            updateAlarm.cancelAllRequests();
            updateAlarm.addRequest(this::rebuildModel, 300);
        }
    }

    private void rebuildModel() {
        if (updateAlarm == null || updateAlarm.isDisposed()) return;
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiMethod method = methodPointer.getElement();
            if (method == null || !method.isValid()) return;
            this.rootBlock = parseMethod(method);
        });
        revalidate();
        repaint();
    }

    private void setupMouseInteractions() {
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    double oldScale = scaleFactor;
                    if (e.getWheelRotation() < 0) scaleFactor *= 1.1;
                    else scaleFactor /= 1.1;
                    scaleFactor = Math.max(MIN_SCALE, Math.min(scaleFactor, MAX_SCALE));
                    double scaleChange = scaleFactor / oldScale;
                    translateX = e.getX() - (e.getX() - translateX) * scaleChange;
                    translateY = e.getY() - (e.getY() - translateY) * scaleChange;
                    revalidate();
                    repaint();
                    e.consume();
                } else {
                    getParent().dispatchEvent(e);
                }
            }
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
                else if (SwingUtilities.isMiddleMouseButton(e)) {
                    lastDragPoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e);
                else if (SwingUtilities.isMiddleMouseButton(e)) {
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
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    handleNavigationClick(e.getX(), e.getY());
                }
            }
        };
        addMouseWheelListener(mouseAdapter);
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem exportItem = new JMenuItem("Export to Image (PNG)");
        exportItem.addActionListener(ev -> exportToImage());

        menu.add(exportItem);
        menu.addSeparator();

        JMenuItem autoItem = new JMenuItem("Match Editor Theme");
        autoItem.addActionListener(ev -> setThemeMode(ThemeMode.AUTO));
        if (currentThemeMode == ThemeMode.AUTO) autoItem.setFont(autoItem.getFont().deriveFont(Font.BOLD));

        JMenuItem darkItem = new JMenuItem("Force Dark Mode");
        darkItem.addActionListener(ev -> setThemeMode(ThemeMode.DARK));
        if (currentThemeMode == ThemeMode.DARK) darkItem.setFont(darkItem.getFont().deriveFont(Font.BOLD));

        JMenuItem lightItem = new JMenuItem("Force Light Mode");
        lightItem.addActionListener(ev -> setThemeMode(ThemeMode.LIGHT));
        if (currentThemeMode == ThemeMode.LIGHT) lightItem.setFont(lightItem.getFont().deriveFont(Font.BOLD));

        menu.add(autoItem);
        menu.addSeparator();
        menu.add(darkItem);
        menu.add(lightItem);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void exportToImage() {
        if (rootBlock == null) return;

        BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gDummy = dummy.createGraphics();
        updateFonts();
        gDummy.setFont(fontPlain);
        Dimension dim = rootBlock.calculateSize(gDummy);
        gDummy.dispose();

        double scale = 4.0;
        int w = (int) ((dim.width + PADDING * 2) * scale);
        int h = (int) ((dim.height + PADDING * 2) * scale);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();

        AffineTransform transform = g2.getTransform();
        transform.scale(scale, scale);
        g2.setTransform(transform);

        g2.setColor(getCurrentBackground());
        g2.fillRect(0, 0, dim.width + PADDING * 2, dim.height + PADDING * 2);

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.2f));

        g2.translate(PADDING, PADDING);
        g2.setColor(getCurrentForeground());
        g2.drawRect(0, 0, dim.width, dim.height);
        rootBlock.draw(g2, 0, 0, dim.width);
        g2.dispose();

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Structuregram");
        fileChooser.setSelectedFile(new File("structuregram.png"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(image, "png", fileChooser.getSelectedFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error saving image: " + ex.getMessage());
            }
        }
    }

    private void setThemeMode(ThemeMode mode) {
        this.currentThemeMode = mode;
        repaint();
    }

    private void handleNavigationClick(int screenX, int screenY) {
        if (rootBlock == null) return;
        double logicalX = (screenX - translateX) / scaleFactor - PADDING;
        double logicalY = (screenY - translateY) / scaleFactor - PADDING;
        Graphics2D g2 = (Graphics2D) getGraphics();
        if (g2 == null) return;
        updateFonts();
        g2.setFont(fontPlain);
        Dimension rootDim = rootBlock.calculateSize(g2);
        PsiElement target = rootBlock.getNavigatableAt(g2, (int)logicalX, (int)logicalY, rootDim.width);

        if (target != null && target.isValid()) {
            PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(target, PsiMethodCallExpression.class);

            if (call == null && target instanceof PsiMethodCallExpression) {
                call = (PsiMethodCallExpression) target;
            }

            if (call != null) {
                PsiMethod resolvedMethod = call.resolveMethod();
                if (resolvedMethod != null && resolvedMethod.isValid() && resolvedMethod instanceof Navigatable) {
                    ((Navigatable) resolvedMethod).navigate(true);
                    return;
                }
            }

            if (target instanceof Navigatable) {
                ((Navigatable) target).navigate(true);
            }
        }
    }

    private void updateFonts() {
        if (fontPlain == null || fontBold == null) {
            Font labelFont = UIUtil.getLabelFont();
            fontPlain = labelFont.deriveFont(Font.PLAIN, 12);
            fontBold = labelFont.deriveFont(Font.BOLD, 12);
        }
    }

    private NsdBlock parseMethod(PsiMethod method) {
        if (method == null || !method.isValid()) return new SimpleBlock(null, "Method invalidated");
        PsiCodeBlock body = method.getBody();
        if (body == null) return new SimpleBlock(method, "Abstract/Native Method");
        return parseCodeBlock(body);
    }

    private NsdBlock parseCodeBlock(PsiCodeBlock block) {
        ContainerBlock container = new ContainerBlock(block);
        for (PsiStatement statement : block.getStatements()) {
            container.add(parseStatement(statement));
        }
        return container;
    }

    private NsdBlock parseStatement(PsiStatement stmt) {
        if (!stmt.isValid()) return new SimpleBlock(null, "Invalid");

        if (stmt instanceof PsiIfStatement) {
            PsiIfStatement ifStmt = (PsiIfStatement) stmt;
            String condition = ifStmt.getCondition() != null ? ifStmt.getCondition().getText() : "?";
            condition = naturalize(condition) + "?";
            NsdBlock thenBlock = parseBody(ifStmt.getThenBranch());
            NsdBlock elseBlock = parseBody(ifStmt.getElseBranch());
            return new IfBlock(stmt, condition, thenBlock, elseBlock);
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
                        if (currentLabel != null && currentContainer != null) {
                            cases.add(new SwitchBlock.CaseInfo(currentLabel, currentContainer));
                        }
                        currentContainer = new ContainerBlock(s);
                        PsiSwitchLabelStatement label = (PsiSwitchLabelStatement) s;
                        if (label.isDefaultCase()) {
                            currentLabel = "Default";
                        } else {
                            PsiCaseLabelElementList list = label.getCaseLabelElementList();
                            currentLabel = (list != null) ? naturalize(list.getText()) : "Case";
                        }
                    } else {
                        if (currentContainer != null) currentContainer.add(parseStatement(s));
                        else if (currentLabel == null) {
                            currentLabel = "Start";
                            currentContainer = new ContainerBlock(s);
                            currentContainer.add(parseStatement(s));
                        }
                    }
                }
                if (currentLabel != null && currentContainer != null) {
                    cases.add(new SwitchBlock.CaseInfo(currentLabel, currentContainer));
                }
            }
            return new SwitchBlock(stmt, naturalize(expression), cases);
        }
        else if (stmt instanceof PsiWhileStatement) {
            PsiWhileStatement loop = (PsiWhileStatement) stmt;
            String condition = loop.getCondition() != null ? loop.getCondition().getText() : "true";
            return new LoopBlock(stmt, "While " + naturalize(condition), parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiDoWhileStatement) {
            PsiDoWhileStatement loop = (PsiDoWhileStatement) stmt;
            String condition = loop.getCondition() != null ? loop.getCondition().getText() : "true";
            return new LoopBlock(stmt, "Do ... Until " + naturalize(condition), parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiForStatement) {
            PsiForStatement loop = (PsiForStatement) stmt;
            String text = "Loop";
            PsiStatement init = loop.getInitialization();
            PsiExpression cond = loop.getCondition();
            if (init != null && cond != null) {
                text = "For " + naturalize(init.getText()) + " to " + naturalize(cond.getText());
            }
            return new LoopBlock(stmt, text, parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiForeachStatement) {
            PsiForeachStatement loop = (PsiForeachStatement) stmt;
            String param = loop.getIterationParameter().getName();
            String value = loop.getIteratedValue() != null ? loop.getIteratedValue().getText() : "?";
            return new LoopBlock(stmt, "For each " + param + " in " + value, parseBody(loop.getBody()));
        }
        else if (stmt instanceof PsiTryStatement) {
            PsiTryStatement tryStmt = (PsiTryStatement) stmt;
            NsdBlock tryBlock = parseBody(tryStmt.getTryBlock());
            List<TryCatchBlock.CatchInfo> catches = new ArrayList<>();
            for (PsiCatchSection section : tryStmt.getCatchSections()) {
                String type = section.getCatchType() != null ? section.getCatchType().getPresentableText() : "Exception";
                catches.add(new TryCatchBlock.CatchInfo(type, parseBody(section.getCatchBlock())));
            }
            NsdBlock finallyBlock = tryStmt.getFinallyBlock() != null ? parseBody(tryStmt.getFinallyBlock()) : null;
            return new TryCatchBlock(stmt, tryBlock, catches, finallyBlock);
        }
        else if (stmt instanceof PsiBlockStatement) {
            return parseCodeBlock(((PsiBlockStatement) stmt).getCodeBlock());
        }
        else if (stmt instanceof PsiReturnStatement) {
            String retVal = ((PsiReturnStatement)stmt).getReturnValue() != null ?
                    ((PsiReturnStatement)stmt).getReturnValue().getText() : "";
            return new SimpleBlock(stmt, "Return " + naturalize(retVal));
        }
        else if (stmt instanceof PsiBreakStatement) {
            return new SimpleBlock(stmt, "Break");
        }
        else if (stmt instanceof PsiContinueStatement) {
            return new SimpleBlock(stmt, "Continue");
        }
        else if (stmt instanceof PsiDeclarationStatement) {
            PsiDeclarationStatement decl = (PsiDeclarationStatement) stmt;
            StringBuilder sb = new StringBuilder();
            for (PsiElement el : decl.getDeclaredElements()) {
                if (el instanceof PsiLocalVariable) {
                    PsiLocalVariable var = (PsiLocalVariable) el;
                    sb.append(var.getName());
                    if (var.getInitializer() != null) {
                        String initText = naturalize(var.getInitializer().getText());

                        sb.append(" := ").append(initText);
                    }
                }
            }
            if (sb.length() == 0) return new SimpleBlock(stmt, naturalize(stmt.getText()));
            return new SimpleBlock(stmt, sb.toString());
        }

        return new SimpleBlock(stmt, naturalize(stmt.getText()));
    }

    private NsdBlock parseBody(PsiStatement body) {
        if (body == null) return new SimpleBlock(null, "");
        if (body instanceof PsiBlockStatement) {
            return parseCodeBlock(((PsiBlockStatement) body).getCodeBlock());
        }
        return parseStatement(body);
    }

    private NsdBlock parseBody(PsiCodeBlock body) {
        if (body == null) return new SimpleBlock(null, "");
        return parseCodeBlock(body);
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

        text = text.replaceAll("\\b(int|String|boolean|double|float|long|var|char|final|void|short|byte)\\b", "").trim();

        text = text.replaceAll("\\.equals\\(([^)]*)\\)", " is equal to $1");
        text = text.replace(".isEmpty()", " is empty");
        text = text.replace(".size()", " size");
        text = text.replace(".length()", " length");

        text = text.replaceAll("\\btrue\\b", "True");
        text = text.replaceAll("\\bfalse\\b", "False");
        text = text.replaceAll("\\bnull\\b", "Nothing");
        text = text.replaceAll("\\bthis\\.", "");


        text = text.replaceAll("([\\w\\.]+)\\s*\\+\\+", "increment $1");
        text = text.replaceAll("\\+\\+([\\w\\.]+)", "increment $1");
        text = text.replaceAll("([\\w\\.]+)\\s*\\-\\-", "decrement $1");
        text = text.replaceAll("\\-\\-([\\w\\.]+)", "decrement $1");

        text = text.replaceAll("([\\w\\.]+)\\s*\\+=\\s*(.+)", "increase $1 by $2");
        text = text.replaceAll("([\\w\\.]+)\\s*\\-=\\s*(.+)", "decrease $1 by $2");


        text = text.replaceAll("(?<![<>=!])=(?![=])", ":=");


        text = text.replace("==", "=");
        text = text.replace("!=", "≠");
        text = text.replace("<=", "≤");
        text = text.replace(">=", "≥");

        text = text.replace(" % ", " mod ");

        text = text.replace("&&", " and ");
        text = text.replace("||", " or ");
        text = text.replace("!", "not ");

        return text.replaceAll("\\s+", " ").trim();
    }

    private List<String> wrapText(Graphics2D g, String text, int maxWidth, Font font) {
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

    private Color getCurrentBackground() {
        switch (currentThemeMode) {
            case DARK: return new Color(43, 43, 43);
            case LIGHT: return Color.WHITE;
            case AUTO: default:
                return EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground();
        }
    }

    private Color getCurrentForeground() {
        switch (currentThemeMode) {
            case DARK: return new Color(187, 187, 187);
            case LIGHT: return Color.BLACK;
            case AUTO: default:
                return EditorColorsManager.getInstance().getGlobalScheme().getDefaultForeground();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(getCurrentBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        updateFonts();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1.2f));

        if (rootBlock != null) {
            g2.setFont(fontPlain);
            Dimension logicalDim = rootBlock.calculateSize(g2);
            int scaledW = (int) ((logicalDim.width + PADDING * 2) * scaleFactor);
            int scaledH = (int) ((logicalDim.height + PADDING * 2) * scaleFactor);
            Dimension currentPref = getPreferredSize();
            if (currentPref.width != scaledW || currentPref.height != scaledH) {
                setPreferredSize(new Dimension(scaledW, scaledH));
                revalidate();
            }
            AffineTransform oldTransform = g2.getTransform();
            g2.translate(translateX, translateY);
            g2.scale(scaleFactor, scaleFactor);
            g2.translate(PADDING, PADDING);
            g2.setColor(getCurrentForeground());
            g2.drawRect(0, 0, logicalDim.width, logicalDim.height);
            rootBlock.draw(g2, 0, 0, logicalDim.width);
            g2.setTransform(oldTransform);
        }
    }

    private static void drawMultilineString(Graphics2D g, List<String> lines, int x, int y, int w, int h, boolean centered) {
        if (lines.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getHeight();
        int totalTextHeight = lines.size() * lineHeight;
        int startY = y + (h - totalTextHeight) / 2 + fm.getAscent();
        for (String line : lines) {
            int textX;
            if (centered) textX = x + (w - fm.stringWidth(line)) / 2;
            else textX = x + 5;
            g.drawString(line, textX, startY);
            startY += lineHeight;
        }
    }

    interface NsdBlock {
        PsiElement getElement();
        Dimension calculateSize(Graphics2D g);
        int draw(Graphics2D g, int x, int y, int width);
        PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width);
    }

    abstract class BaseBlock implements NsdBlock {
        protected PsiElement element;
        public BaseBlock(PsiElement element) { this.element = element; }
        @Override public PsiElement getElement() { return element; }
        protected boolean isPointInside(Graphics2D g, int x, int y, int w, int relX, int relY) {
            Dimension size = calculateSize(g);
            return relX >= x && relX <= x + w && relY >= y && relY <= y + size.height;
        }
    }

    class ContainerBlock extends BaseBlock {
        List<NsdBlock> children = new ArrayList<>();
        ContainerBlock(PsiElement element) { super(element); }
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
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (!isPointInside(g, 0, 0, width, x, y)) return null;
            int currentY = 0;
            for (NsdBlock b : children) {
                PsiElement found = b.getNavigatableAt(g, x, y - currentY, width);
                if (found != null) return found;
                currentY += b.calculateSize(g).height;
            }
            return null;
        }
    }

    class SimpleBlock extends BaseBlock {
        String text;
        SimpleBlock(PsiElement element, String text) { super(element); this.text = text; }
        @Override
        public Dimension calculateSize(Graphics2D g) {
            int textWidth = g.getFontMetrics(fontPlain).stringWidth(text);
            int width = Math.max(MIN_BLOCK_WIDTH, Math.min(textWidth + 20, 500));
            List<String> lines = wrapText(g, text, width - 20, fontPlain);
            int height = Math.max(LINE_HEIGHT, lines.size() * (g.getFontMetrics(fontPlain).getHeight() + 2) + BLOCK_PADDING);
            return new Dimension(width, height);
        }
        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            Dimension size = calculateSize(g);
            int height = size.height;
            g.setColor(getCurrentForeground());
            g.drawRect(x, y, width, height);
            g.setFont(fontPlain);
            List<String> lines = wrapText(g, text, width - 20, fontPlain);
            drawMultilineString(g, lines, x, y, width, height, false);
            return height;
        }
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (isPointInside(g, 0, 0, width, x, y)) return element;
            return null;
        }
    }

    class IfBlock extends BaseBlock {
        String condition;
        NsdBlock thenBlock, elseBlock;
        IfBlock(PsiElement element, String condition, NsdBlock thenBlock, NsdBlock elseBlock) {
            super(element);
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
            g.setColor(getCurrentForeground());
            g.drawRect(x, y, width, headerH);
            int midX = x + (width / 2);
            int bottomY = y + headerH;
            g.drawLine(x, y, midX, bottomY);
            g.drawLine(x + width, y, midX, bottomY);
            g.setFont(fontBold);
            drawMultilineString(g, wrapText(g, condition, width/2, fontBold), x, y, width, headerH/2 + 5, true);
            g.setFont(fontPlain);
            g.drawString("True", x + 5, bottomY - 5);
            g.drawString("False", x + width - 35, bottomY - 5);
            thenBlock.draw(g, x, bottomY, width / 2);
            elseBlock.draw(g, midX, bottomY, width / 2);
            g.drawRect(x, bottomY, width / 2, contentH);
            g.drawRect(midX, bottomY, width / 2, contentH);
            return headerH + contentH;
        }
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (!isPointInside(g, 0, 0, width, x, y)) return null;
            int headerH = (int)(LINE_HEIGHT * 2.5);
            if (y < headerH) return element;
            int midX = width / 2;
            if (x < midX) return thenBlock.getNavigatableAt(g, x, y - headerH, midX);
            else return elseBlock.getNavigatableAt(g, x - midX, y - headerH, midX);
        }
    }

    class LoopBlock extends BaseBlock {
        String condition;
        NsdBlock body;
        LoopBlock(PsiElement element, String condition, NsdBlock body) {
            super(element);
            this.condition = condition;
            this.body = body;
        }
        @Override
        public Dimension calculateSize(Graphics2D g) {
            Dimension b = body.calculateSize(g);
            int barWidth = 30;
            int contentWidth = Math.max(b.width + barWidth, MIN_BLOCK_WIDTH);
            List<String> lines = wrapText(g, condition, contentWidth - 10, fontBold);
            int headerHeight = Math.max(LINE_HEIGHT + 10, lines.size() * g.getFontMetrics(fontBold).getHeight() + 10);
            return new Dimension(contentWidth, b.height + headerHeight);
        }
        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int barWidth = 30;
            List<String> lines = wrapText(g, condition, width - 10, fontBold);
            int headerHeight = Math.max(LINE_HEIGHT + 10, lines.size() * g.getFontMetrics(fontBold).getHeight() + 10);
            int bodyHeight = body.calculateSize(g).height;
            int totalHeight = bodyHeight + headerHeight;
            g.setColor(getCurrentForeground());
            g.drawLine(x, y, x + width, y);
            g.drawLine(x, y, x, y + totalHeight);
            g.drawLine(x + width, y, x + width, y + headerHeight);
            g.drawLine(x, y + totalHeight, x + barWidth, y + totalHeight);
            g.drawLine(x + barWidth, y + headerHeight, x + width, y + headerHeight);
            g.drawLine(x + barWidth, y + headerHeight, x + barWidth, y + totalHeight);
            g.setFont(fontBold);
            drawMultilineString(g, lines, x, y, width, headerHeight, false);
            body.draw(g, x + barWidth, y + headerHeight, width - barWidth);
            return totalHeight;
        }
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (!isPointInside(g, 0, 0, width, x, y)) return null;
            List<String> lines = wrapText(g, condition, width - 10, fontBold);
            int headerHeight = Math.max(LINE_HEIGHT + 10, lines.size() * g.getFontMetrics(fontBold).getHeight() + 10);
            if (y < headerHeight) return element;
            int barWidth = 30;
            if (x < barWidth) return element;
            return body.getNavigatableAt(g, x - barWidth, y - headerHeight, width - barWidth);
        }
    }

    class SwitchBlock extends BaseBlock {
        static class CaseInfo {
            String label;
            ContainerBlock block;
            CaseInfo(String label, ContainerBlock block) { this.label = label; this.block = block; }
        }
        String expression;
        List<CaseInfo> cases;
        SwitchBlock(PsiElement element, String expression, List<CaseInfo> cases) {
            super(element);
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
            g.setColor(getCurrentForeground());
            g.drawRect(x, y, width, headerH);
            g.setFont(fontBold);
            drawMultilineString(g, wrapText(g, "Switch: " + expression, width, fontBold), x, y + 5, width, headerH/2, true);
            if (cases.isEmpty()) return headerH;
            int currentX = x;
            int remainingWidth = width;
            int totalRequiredWidth = 0;
            for(CaseInfo c : cases) totalRequiredWidth += c.block.calculateSize(g).width;
            int maxContentH = 0;
            for(CaseInfo c : cases) maxContentH = Math.max(maxContentH, c.block.calculateSize(g).height);
            for (int i = 0; i < cases.size(); i++) {
                CaseInfo c = cases.get(i);
                int colWidth;
                if (i == cases.size() - 1) colWidth = remainingWidth;
                else {
                    double ratio = (double) c.block.calculateSize(g).width / totalRequiredWidth;
                    colWidth = (int) (width * ratio);
                    if (colWidth < 50) colWidth = 50;
                }
                remainingWidth -= colWidth;
                g.drawRect(currentX, contentTop - (headerH/2), colWidth, headerH/2);
                g.setFont(fontPlain);
                g.drawString(c.label, currentX + 5, contentTop - 5);
                c.block.draw(g, currentX, contentTop, colWidth);
                g.drawRect(currentX, contentTop, colWidth, maxContentH);
                currentX += colWidth;
            }
            return headerH + maxContentH;
        }
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (!isPointInside(g, 0, 0, width, x, y)) return null;
            int headerH = (int)(LINE_HEIGHT * 2.5);
            if (y < headerH) return element;
            if (cases.isEmpty()) return null;
            int currentX = 0;
            int remainingWidth = width;
            int totalRequiredWidth = 0;
            for(CaseInfo c : cases) totalRequiredWidth += c.block.calculateSize(g).width;
            for (int i = 0; i < cases.size(); i++) {
                CaseInfo c = cases.get(i);
                int colWidth;
                if (i == cases.size() - 1) {
                    colWidth = remainingWidth;
                } else {
                    double ratio = (double) c.block.calculateSize(g).width / totalRequiredWidth;
                    colWidth = (int) (width * ratio);
                    if (colWidth < 50) colWidth = 50;
                }
                remainingWidth -= colWidth;
                if (x >= currentX && x < currentX + colWidth) {
                    return c.block.getNavigatableAt(g, x - currentX, y - headerH, colWidth);
                }
                currentX += colWidth;
            }
            return null;
        }
    }

    class TryCatchBlock extends BaseBlock {
        static class CatchInfo {
            String type;
            NsdBlock block;
            CatchInfo(String type, NsdBlock block) { this.type = type; this.block = block; }
        }
        NsdBlock tryBlock;
        List<CatchInfo> catches;
        NsdBlock finallyBlock;
        TryCatchBlock(PsiElement element, NsdBlock tryBlock, List<CatchInfo> catches, NsdBlock finallyBlock) {
            super(element);
            this.tryBlock = tryBlock;
            this.catches = catches;
            this.finallyBlock = finallyBlock;
        }
        @Override
        public Dimension calculateSize(Graphics2D g) {
            Dimension t = tryBlock.calculateSize(g);
            int w = t.width;
            int h = t.height + LINE_HEIGHT;
            if (!catches.isEmpty()) {
                int cW = 0;
                int cH = 0;
                for (CatchInfo c : catches) {
                    Dimension d = c.block.calculateSize(g);
                    cW += d.width;
                    cH = Math.max(cH, d.height);
                }
                w = Math.max(w, cW);
                h += cH + LINE_HEIGHT;
            }
            if (finallyBlock != null) {
                Dimension f = finallyBlock.calculateSize(g);
                w = Math.max(w, f.width);
                h += f.height + LINE_HEIGHT;
            }
            return new Dimension(Math.max(w, MIN_BLOCK_WIDTH), h);
        }
        @Override
        public int draw(Graphics2D g, int x, int y, int width) {
            int currentY = y;
            g.setColor(getCurrentForeground());
            g.drawRect(x, currentY, width, LINE_HEIGHT);
            g.setFont(fontBold);
            g.drawString("Try", x + 5, currentY + 15);
            currentY += LINE_HEIGHT;
            int tryH = tryBlock.draw(g, x, currentY, width);
            currentY += tryH;
            if (!catches.isEmpty()) {
                g.drawRect(x, currentY, width, LINE_HEIGHT);
                currentY += LINE_HEIGHT;
                int catchTop = currentY;
                int remainingWidth = width;
                int totalRequiredWidth = 0;
                for (CatchInfo c : catches) totalRequiredWidth += c.block.calculateSize(g).width;
                int maxH = 0;
                for (CatchInfo c : catches) maxH = Math.max(maxH, c.block.calculateSize(g).height);
                int curX = x;
                for (int i = 0; i < catches.size(); i++) {
                    CatchInfo c = catches.get(i);
                    int colWidth;
                    if (i == catches.size() - 1) colWidth = remainingWidth;
                    else {
                        double ratio = (double) c.block.calculateSize(g).width / totalRequiredWidth;
                        colWidth = (int) (width * ratio);
                        if (colWidth < 50) colWidth = 50;
                    }
                    remainingWidth -= colWidth;
                    g.setFont(fontPlain);
                    g.drawString("Catch " + c.type, curX + 5, catchTop - 5);
                    g.drawLine(curX, catchTop - LINE_HEIGHT, curX, catchTop + maxH);
                    c.block.draw(g, curX, catchTop, colWidth);
                    curX += colWidth;
                }
                g.drawRect(x, catchTop, width, maxH);
                currentY += maxH;
            }
            if (finallyBlock != null) {
                g.drawRect(x, currentY, width, LINE_HEIGHT);
                g.setFont(fontBold);
                g.drawString("Finally", x + 5, currentY + 15);
                currentY += LINE_HEIGHT;
                int finH = finallyBlock.draw(g, x, currentY, width);
                currentY += finH;
            }
            return currentY - y;
        }
        @Override
        public PsiElement getNavigatableAt(Graphics2D g, int x, int y, int width) {
            if (!isPointInside(g, 0, 0, width, x, y)) return null;
            int currentY = 0;
            if (y < LINE_HEIGHT) return element;
            currentY += LINE_HEIGHT;
            int tryH = tryBlock.calculateSize(g).height;
            if (y < currentY + tryH) return tryBlock.getNavigatableAt(g, x, y - currentY, width);
            currentY += tryH;
            if (!catches.isEmpty()) {
                if (y < currentY + LINE_HEIGHT) return element;
                currentY += LINE_HEIGHT;
                int maxH = 0;
                int totalRequiredWidth = 0;
                for (CatchInfo c : catches) {
                    Dimension d = c.block.calculateSize(g);
                    maxH = Math.max(maxH, d.height);
                    totalRequiredWidth += d.width;
                }
                if (y < currentY + maxH) {
                    int relX = x;
                    int remainingW = width;
                    for (int i = 0; i < catches.size(); i++) {
                        CatchInfo c = catches.get(i);
                        int colWidth;
                        if (i == catches.size() - 1) colWidth = remainingW;
                        else {
                            double ratio = (double) c.block.calculateSize(g).width / totalRequiredWidth;
                            colWidth = (int) (width * ratio);
                            if (colWidth < 50) colWidth = 50;
                        }
                        remainingW -= colWidth;
                        if (relX < colWidth) return c.block.getNavigatableAt(g, relX, y - currentY, colWidth);
                        relX -= colWidth;
                    }
                }
                currentY += maxH;
            }
            if (finallyBlock != null) {
                if (y < currentY + LINE_HEIGHT) return element;
                currentY += LINE_HEIGHT;
                return finallyBlock.getNavigatableAt(g, x, y - currentY, width);
            }
            return null;
        }
    }
}