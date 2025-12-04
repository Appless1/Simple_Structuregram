
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class GenerateStructuregramAction extends AnAction {

    @Override
    public void update(AnActionEvent e) {
        // This method determines if the menu item is visible or grayed out
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        boolean visible = false;
        if (project != null && editor != null && psiFile != null) {
            visible = findMethod(editor, psiFile) != null;
        }

        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || psiFile == null) return;

        PsiMethod method = findMethod(editor, psiFile);

        if (method != null) {
            StructuregramDialog dialog = new StructuregramDialog(method);
            dialog.show();
        }
    }

    // Helper to robustly find a method based on caret OR selection
    private PsiMethod findMethod(Editor editor, PsiFile psiFile) {
        // 1. Try the current Caret position
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

        // 2. If null, and we have a selection, try the START of the selection
        // (This fixes the issue where you highlight a method but the cursor ends up outside it)
        if (method == null && editor.getSelectionModel().hasSelection()) {
            int startOffset = editor.getSelectionModel().getSelectionStart();
            PsiElement startElement = psiFile.findElementAt(startOffset);
            method = PsiTreeUtil.getParentOfType(startElement, PsiMethod.class);
        }

        return method;
    }

    private static class StructuregramDialog extends DialogWrapper {
        private final PsiMethod method;

        protected StructuregramDialog(PsiMethod method) {
            super(true);
            this.method = method;
            init();
            setTitle("Structuregram: " + method.getName());
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            StructuregramComponent viewer = new StructuregramComponent(method);
            JScrollPane scrollPane = new JScrollPane(viewer);
            scrollPane.setPreferredSize(new Dimension(800, 600));
            return scrollPane;
        }
    }
}