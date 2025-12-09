import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.util.Disposer;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StructuregramComponent extends JPanel {

    // --- Enums & Constants ---
    public enum ThemeMode { AUTO, DARK, LIGHT }
    private static final int PADDING = 20;
    private static final int LINE_HEIGHT = 20;
    private static final int BLOCK_PADDING = 10;
    private static final int MIN_BLOCK_WIDTH = 250;
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 5.0;

    // --- State ---
    private final SmartPsiElementPointer<PsiMethod> methodPointer;
    private NsdBlock rootBlock;
    private Alarm updateAlarm;
    private Disposable connectionDisposable;
    private PsiTreeChangeListener psiListener;

    private ThemeMode currentThemeMode = ThemeMode.AUTO;
    private Font fontPlain;
    private Font fontBold;

    // --- Transformation & Navigation ---
    private double scaleFactor = 1.0;
    private double translateX = 0;
    private double translateY = 0;
    private Point lastDragPoint;

    // --- Editing & Interaction ---
    private boolean isEditMode = false;
    private final Map<Rectangle, NsdBlock> hitMap = new HashMap<>(); // Logical coordinates
    private NsdBlock highlightedBlock = null; // For DnD feedback

    // --- UI Components ---
    private final CanvasPanel canvasPanel;
    private final JPanel palettePanel;
    private final JToolBar toolbar;
    private final JToggleButton editToggle;

    public StructuregramComponent(PsiMethod method) {
        setLayout(new BorderLayout());

        this.methodPointer = SmartPointerManager.getInstance(method.getProject())
                .createSmartPsiElementPointer(method);

        // create internal canvas
        this.canvasPanel = new CanvasPanel();

        // Setup Toolbar & Palette
        toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(5, 5, 5, 5));

        // Better looking Edit Toggle
        editToggle = new JToggleButton("Edit Mode");
        editToggle.setFocusPainted(false);
        editToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editToggle.addActionListener(e -> setEditMode(editToggle.isSelected()));

        toolbar.add(editToggle);
        toolbar.add(Box.createHorizontalGlue()); // Push everything to left

        palettePanel = createPalette();
        palettePanel.setVisible(false);

        add(toolbar, BorderLayout.NORTH);
        add(palettePanel, BorderLayout.WEST);
        add(new JBScrollPane(canvasPanel), BorderLayout.CENTER);

        // Initial parse
        ApplicationManager.getApplication().runReadAction(() -> {
            this.rootBlock = parseMethod(method);
        });

        // Setup Listeners
        setupPsiListener();
    }

    private void setThemeMode(ThemeMode mode) {
        this.currentThemeMode = mode;
        canvasPanel.revalidate();
        canvasPanel.repaint();
    }

    private void setEditMode(boolean enabled) {
        this.isEditMode = enabled;
        palettePanel.setVisible(enabled);
        canvasPanel.setTransferHandler(enabled ? new BlockTransferHandler() : null);

        // Visual feedback for edit mode on the button
        if (enabled) {
            editToggle.setBackground(new JBColor(new Color(0xE0E0E0), new Color(0x4C5052)));
            editToggle.setFont(editToggle.getFont().deriveFont(Font.BOLD));
        } else {
            editToggle.setBackground(null);
            editToggle.setFont(editToggle.getFont().deriveFont(Font.PLAIN));
        }

        revalidate();
        repaint();
    }

    private JPanel createPalette() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(new JLabel("Drag Blocks:"));
        p.add(Box.createVerticalStrut(10));

        // Use 'var' for simpler variable creation
        createDraggableLabel(p, "Variable", "var a = 0;");
        createDraggableLabel(p, "Assignment", "a = 10;");
        createDraggableLabel(p, "If Statement", "if (true) {\n} else {\n}");
        createDraggableLabel(p, "While Loop", "while (true) {\n}");
        createDraggableLabel(p, "For Loop", "for (int i=0; i<10; i++) {\n}");
        createDraggableLabel(p, "Print", "System.out.println(\"Text\");");

        return p;
    }

    private void createDraggableLabel(JPanel p, String title, String templateCode) {
        JLabel lbl = new JLabel(title);
        lbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setMaximumSize(new Dimension(150, 30));

        lbl.setTransferHandler(new TransferHandler("text"));
        lbl.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JComponent c = (JComponent) e.getSource();
                TransferHandler handler = c.getTransferHandler();
                handler.exportAsDrag(c, e, TransferHandler.COPY);
            }
        });

        // Store template in client property
        lbl.putClientProperty("template", templateCode);

        // Custom Transferable logic would be here, but using StringSelection for simplicity
        // We override exportAsDrag behaviour via the handler or simple string copy
        lbl.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                return new StringSelection(templateCode);
            }
        });

        p.add(lbl);
        p.add(Box.createVerticalStrut(5));
    }

    // --- Inner Class for the actual drawing surface ---
    private class CanvasPanel extends JPanel {

        public CanvasPanel() {
            setOpaque(true);
            setFocusable(true);
            setupMouseInteractions();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            // Sync Theme Colors to Parent UI
            Color bg = getCurrentBackground();
            Color fg = getCurrentForeground();

            StructuregramComponent.this.setBackground(bg);
            if (toolbar != null) {
                toolbar.setBackground(bg);
            }
            if (palettePanel != null) {
                palettePanel.setBackground(bg);
                for (Component c : palettePanel.getComponents()) {
                    c.setForeground(fg); // Update text color of palette labels
                    if (c instanceof JComponent) {
                        ((JComponent)c).setOpaque(false); // Make labels transparent to show panel bg
                    }
                }
            }

            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());

            updateFonts();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.2f));

            if (rootBlock != null) {
                g2.setFont(fontPlain);

                // Clear hit map before redraw
                hitMap.clear();

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

                // DRAW and populate HitMap
                rootBlock.draw(g2, 0, 0, logicalDim.width);

                // Draw Drag Highlight
                if (isEditMode && highlightedBlock != null) {
                    for (Map.Entry<Rectangle, NsdBlock> entry : hitMap.entrySet()) {
                        if (entry.getValue() == highlightedBlock) {
                            Rectangle r = entry.getKey();
                            g2.setColor(new Color(62, 134, 255, 128)); // Blue transparent
                            // Highlight the bottom edge to indicate "insert after"
                            g2.fillRect(r.x, r.y + r.height - 5, r.width, 5);
                        }
                    }
                }

                g2.setTransform(oldTransform);
            }
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
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (e.getClickCount() == 2 && isEditMode) {
                            handleBlockEdit(e.getX(), e.getY());
                        } else if (e.getClickCount() == 1) {
                            handleNavigationClick(e.getX(), e.getY());
                        }
                    }
                }
            };
            addMouseWheelListener(mouseAdapter);
            addMouseListener(mouseAdapter);
            addMouseMotionListener(mouseAdapter);
        }
    }

    // --- Lifecycle & Plumbing ---

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
            @Override public void childrenChanged(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override public void childAdded(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override public void childRemoved(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override public void childReplaced(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
            @Override public void childMoved(@NotNull PsiTreeChangeEvent event) { scheduleUpdate(event); }
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
        canvasPanel.revalidate();
        canvasPanel.repaint();
    }

    // --- Logic & Helpers ---

    private NsdBlock getBlockAt(int screenX, int screenY) {
        double logicalX = (screenX - translateX) / scaleFactor - PADDING;
        double logicalY = (screenY - translateY) / scaleFactor - PADDING;

        // Simple linear search through rectangles
        // In a production app with thousands of blocks, use a QuadTree.
        for (Map.Entry<Rectangle, NsdBlock> entry : hitMap.entrySet()) {
            if (entry.getKey().contains(logicalX, logicalY)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void handleBlockEdit(int screenX, int screenY) {
        NsdBlock block = getBlockAt(screenX, screenY);
        if (block == null || block.getElement() == null) return;

        PsiElement element = block.getElement();

        // Setup Editor Popup
        JTextArea textArea = new JTextArea(element.getText());
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setRows(5);
        textArea.setColumns(30);

        int result = JOptionPane.showConfirmDialog(this, new JBScrollPane(textArea),
                "Edit Code", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String newCode = textArea.getText();
            applyCodeChange(element, newCode);
        }
    }

    private void applyCodeChange(PsiElement oldElement, String newCode) {
        Project project = oldElement.getProject();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                PsiElement parent = oldElement.getParent();

                // Heuristic: Try to create statement first, if fails, try expression (for conditions)
                PsiElement newElement;
                if (oldElement instanceof PsiStatement) {
                    newElement = factory.createStatementFromText(newCode, parent);
                } else if (oldElement instanceof PsiExpression) {
                    newElement = factory.createExpressionFromText(newCode, parent);
                } else {
                    // Fallback to statement
                    newElement = factory.createStatementFromText(newCode, parent);
                }

                if (oldElement.isValid()) {
                    oldElement.replace(newElement);
                    CodeStyleManager.getInstance(project).reformat(newElement.getParent());
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Invalid Code: " + e.getMessage());
            }
        });
    }

    private void deleteElement(PsiElement element) {
        if (element == null || !element.isValid()) return;
        Project project = element.getProject();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                element.delete();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Could not delete block: " + e.getMessage());
            }
        });
    }

    private void insertCodeAfter(NsdBlock targetBlock, String codeTemplate) {
        if (targetBlock == null) return;
        PsiElement anchor = targetBlock.getElement();
        if (anchor == null || !anchor.isValid()) return;

        // If the block is a container or structure, we usually want to append AFTER it.
        // However, if the anchor is a method body (from root), we add to body.

        Project project = anchor.getProject();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                PsiStatement newStmt = factory.createStatementFromText(codeTemplate, null);

                PsiElement container = anchor.getParent();

                if (container instanceof PsiCodeBlock) {
                    container.addAfter(newStmt, anchor);
                    CodeStyleManager.getInstance(project).reformat(newStmt);
                } else {
                    // Edge case: if selection is weird, try to find the nearest code block
                    PsiCodeBlock block = PsiTreeUtil.getParentOfType(anchor, PsiCodeBlock.class);
                    if (block != null) {
                        block.addAfter(newStmt, anchor);
                    }
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Could not insert block: " + e.getMessage());
            }
        });
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JBPopupMenu();

        // --- Edit Mode Actions ---
        if (isEditMode) {
            NsdBlock block = getBlockAt(e.getX(), e.getY());
            if (block != null && block.getElement() != null) {
                JMenuItem deleteItem = new JBMenuItem("Delete Block");
                deleteItem.addActionListener(ev -> deleteElement(block.getElement()));
                menu.add(deleteItem);
                menu.addSeparator();
            }
        }

        // --- View Actions ---
        JMenuItem exportItem = new JBMenuItem("Export to Image (PNG)");
        exportItem.addActionListener(ev -> exportToImage());
        menu.add(exportItem);
        menu.addSeparator();

        JMenuItem autoItem = new JBMenuItem("Match Editor Theme");
        autoItem.addActionListener(ev -> setThemeMode(ThemeMode.AUTO));
        if (currentThemeMode == ThemeMode.AUTO) autoItem.setFont(autoItem.getFont().deriveFont(Font.BOLD));

        JMenuItem darkItem = new JBMenuItem("Force Dark Mode");
        darkItem.addActionListener(ev -> setThemeMode(ThemeMode.DARK));
        if (currentThemeMode == ThemeMode.DARK) darkItem.setFont(darkItem.getFont().deriveFont(Font.BOLD));

        JMenuItem lightItem = new JBMenuItem("Force Light Mode");
        lightItem.addActionListener(ev -> setThemeMode(ThemeMode.LIGHT));
        if (currentThemeMode == ThemeMode.LIGHT) lightItem.setFont(lightItem.getFont().deriveFont(Font.BOLD));

        menu.add(autoItem);
        menu.addSeparator();
        menu.add(darkItem);
        menu.add(lightItem);

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void handleNavigationClick(int screenX, int screenY) {
        if (isEditMode) return; // Don't navigate in edit mode, might conflict with selection
        NsdBlock target = getBlockAt(screenX, screenY);
        if (target != null && target.getElement() instanceof Navigatable) {
            ((Navigatable) target.getElement()).navigate(true);
        }
    }

    // --- Transfer Handler for DnD ---

    private class BlockTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                String data = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                Point p = support.getDropLocation().getDropPoint();
                NsdBlock target = getBlockAt(p.x, p.y);
                if (target != null) {
                    insertCodeAfter(target, data);
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        }
    }

    // --- Drawing & Export Helper Methods ---

    private void exportToImage() {
        if (rootBlock == null) return;
        BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gDummy = dummy.createGraphics();
        updateFonts();
        gDummy.setFont(fontPlain);
        // Clear hitmap so it doesn't get populated by export calc
        hitMap.clear();
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

        // Draw without populating interactive hitmap
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

    private void updateFonts() {
        if (fontPlain == null || fontBold == null) {
            Font labelFont = UIUtil.getLabelFont();
            fontPlain = labelFont.deriveFont(Font.PLAIN, 12);
            fontBold = labelFont.deriveFont(Font.BOLD, 12);
        }
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

    // --- Parsing Logic (Kept mostly same, adjusted for Robustness) ---

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
        else if (stmt instanceof PsiExpressionStatement) {
            // Specifically handling assignments and calls cleanly
            return new SimpleBlock(stmt, naturalize(stmt.getText()));
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

    // --- Text Naturalization Helpers ---
    private String naturalize(String code) {
        if (code == null) return "";
        String text = code.trim();
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1);

        // Basic cleanup for display
        text = text.replaceAll("\\b(int|String|boolean|double|float|long|var|char|final|void|short|byte)\\b", "").trim();
        text = text.replaceAll("\\btrue\\b", "True");
        text = text.replaceAll("\\bfalse\\b", "False");
        text = text.replace("==", "equals");
        text = text.replace("!=", "≠");
        text = text.replace("<=", "≤");
        text = text.replace(">=", "≥");
        text = text.replace("&&", " and ");
        text = text.replace("||", " or ");
        text = text.replace("!", "not ");

        // Handle assignments for display
        if (text.contains("=") && !text.contains(":=") && !text.contains("≤") && !text.contains("≥") && !text.contains("≠")) {
            text = text.replaceFirst("=", ":=");
        }

        return text.replaceAll("\\s+", " ").trim();
    }

    // --- String Wrapping Helper ---
    private List<String> wrapText(Graphics2D g, String text, int maxWidth, Font font) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        FontMetrics fm = g.getFontMetrics(font);
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            String potential = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (fm.stringWidth(potential) <= maxWidth) {
                if (currentLine.length() > 0) currentLine.append(" ");
                currentLine.append(word);
            } else {
                if (currentLine.length() > 0) lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) lines.add(currentLine.toString());
        return lines;
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

    // --- Interfaces & Block Classes (With Hit Mapping) ---

    interface NsdBlock {
        PsiElement getElement();
        Dimension calculateSize(Graphics2D g);
        int draw(Graphics2D g, int x, int y, int width);
    }

    abstract class BaseBlock implements NsdBlock {
        protected PsiElement element;
        public BaseBlock(PsiElement element) { this.element = element; }
        @Override public PsiElement getElement() { return element; }

        // Helper to register the block's area in the hit map
        protected void registerHit(int x, int y, int w, int h) {
            hitMap.put(new Rectangle(x, y, w, h), this);
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
            // Container doesn't register a hit for itself, only passes to children
            // unless it's empty
            if (children.isEmpty()) {
                registerHit(x, y, width, 20);
                return 20;
            }

            int currentY = y;
            for (NsdBlock b : children) {
                int h = b.draw(g, x, currentY, width);
                currentY += h;
            }
            return currentY - y;
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

            // Register Hit
            registerHit(x, y, width, height);

            g.setFont(fontPlain);
            List<String> lines = wrapText(g, text, width - 20, fontPlain);
            drawMultilineString(g, lines, x, y, width, height, false);
            return height;
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

            // Register Hit for the Header (Condition)
            registerHit(x, y, width, headerH);

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

            // Register Hit for Header
            registerHit(x, y, width, headerHeight);
            // Register Hit for side bar
            registerHit(x, y + headerHeight, barWidth, bodyHeight);

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

            // Hit Map Header
            registerHit(x, y, width, headerH);

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

            // Try Header
            g.drawRect(x, currentY, width, LINE_HEIGHT);
            registerHit(x, currentY, width, LINE_HEIGHT);

            g.setFont(fontBold);
            g.drawString("Try", x + 5, currentY + 15);
            currentY += LINE_HEIGHT;

            int tryH = tryBlock.draw(g, x, currentY, width);
            currentY += tryH;

            if (!catches.isEmpty()) {
                g.drawRect(x, currentY, width, LINE_HEIGHT);
                registerHit(x, currentY, width, LINE_HEIGHT);

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
                registerHit(x, currentY, width, LINE_HEIGHT);

                g.setFont(fontBold);
                g.drawString("Finally", x + 5, currentY + 15);
                currentY += LINE_HEIGHT;
                int finH = finallyBlock.draw(g, x, currentY, width);
                currentY += finH;
            }
            return currentY - y;
        }
    }
}