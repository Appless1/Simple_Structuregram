import com.intellij.openapi.actionSystem.ActionUpdateThread;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class GenerateStructuregramAction extends AnAction {


    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || psiFile == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }


        if (!psiFile.getLanguage().getID().equals("JAVA")) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        e.getPresentation().setVisible(true);

        PsiMethod method = findMethod(editor, psiFile);
        boolean hasMethod = (method != null);

        e.getPresentation().setEnabled(hasMethod);

        if (hasMethod) {
            e.getPresentation().setText("Generate Structuregram");
        } else {
            e.getPresentation().setText("Generate Structuregram (Select Method)");
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null || psiFile == null) return;

        PsiMethod method = findMethod(editor, psiFile);

        if (method != null) {
            StructuregramDialog dialog = new StructuregramDialog(method);
            dialog.show();
        } else {
            JOptionPane.showMessageDialog(null, "No method found at cursor.");
        }
    }

    private PsiMethod findMethod(Editor editor, PsiFile psiFile) {
        if (editor == null || psiFile == null) return null;

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);

        if (element == null) return null;

        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

        if (method == null) {
            PsiElement parent = element.getParent();
            if (parent != null) {
                method = PsiTreeUtil.getParentOfType(parent, PsiMethod.class);
            }
        }

        if (method == null && element.getParent() instanceof PsiMethod) {
            method = (PsiMethod) element.getParent();
        }

        return method;
    }

    private static class StructuregramDialog extends DialogWrapper {
        private final PsiMethod method;

        protected StructuregramDialog(PsiMethod method) {
            super(method.getProject()); // Use project context for better dialog handling
            this.method = method;
            init();
            setTitle("Structuregram: " + method.getName());
            setModal(false); // allow interaction with code while viewing
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            StructuregramComponent viewer = new StructuregramComponent(method);
            JScrollPane scrollPane = new JScrollPane(viewer);
            scrollPane.setPreferredSize(new Dimension(800, 600));

            // Speed up scrolling for large diagrams
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
            scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

            return scrollPane;
        }
    }
}