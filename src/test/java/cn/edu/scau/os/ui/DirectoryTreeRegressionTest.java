package cn.edu.scau.os.ui;

import cn.edu.scau.os.disk.FileSystem;

import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.List;

/** 无需测试框架；在 Swing EDT 上运行，可使用 -Djava.awt.headless=true。 */
public final class DirectoryTreeRegressionTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(DirectoryTreeRegressionTest::verify);
        System.out.println("Directory tree regression checks passed");
    }

    private static void verify() {
        DirectoryTree tree = new DirectoryTree();
        tree.update(snapshot(1, false));
        TreePath aaSub = path(tree, "aa", "sub");
        TreePath bb = path(tree, "bb");
        check(tree.isExpanded(aaSub), "First refresh expands nested folders");
        tree.collapsePath(bb);
        tree.collapsePath(aaSub);
        tree.expandPath(aaSub);
        tree.setSelectionPath(path(tree, "aa", "sub", "txt"));
        Object model = tree.getModel();
        for (int i = 0; i < 20; i++) {
            tree.update(snapshot(1, false));
            check(tree.getModel() == model, "Periodic refresh retains the model");
            check(tree.isExpanded(aaSub), "Expanded folder stays expanded");
            check(tree.isCollapsed(bb), "Collapsed folder stays collapsed");
            check(tree.isPathSelected(path(tree, "aa", "sub", "txt")), "Selection survives refresh");
        }

        tree.update(snapshot(2, true));
        check(tree.isExpanded(path(tree, "aa", "sub")), "File insertion preserves nested expansion");
        check(tree.isCollapsed(path(tree, "bb")), "Same folder name in another branch stays collapsed");
        TreePath selected = path(tree, "aa", "sub", "txt");
        check(tree.isPathSelected(selected), "File size changes preserve selection by path");
        check(selected.getLastPathComponent().toString().contains("2B"), "Updated metadata is displayed");
        check(path(tree, "aa", "new") != null, "New files are displayed");

        tree.collapsePath(path(tree, "aa"));
        tree.update(snapshot(3, false));
        check(tree.isCollapsed(path(tree, "aa")), "Restoring selection does not expand its parent");
        check(tree.isPathSelected(path(tree, "aa")), "Collapsed folder selection survives changes");
        check(((DefaultMutableTreeNode) path(tree, "aa").getLastPathComponent()).getChildCount() == 1,
                "Deleted files disappear");

        tree.collapsePath(new TreePath(tree.getModel().getRoot()));
        tree.update(snapshot(4, false));
        check(tree.isCollapsed(new TreePath(tree.getModel().getRoot())), "Root can remain collapsed");
        tree.setExpandsSelectedPaths(false);
        tree.setSelectionPath(path(tree, "aa"));
        tree.update(List.of());
        check(((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildCount() == 0,
                "Empty snapshots clear stale entries");
        check(tree.getSelectionCount() == 0, "Deleted selections are cleared");
    }

    private static List<FileSystem.DirNode> snapshot(int size, boolean extra) {
        FileSystem.DirNode txt = new FileSystem.DirNode("txt", false, 7, size, "普通", List.of());
        FileSystem.DirNode sub = new FileSystem.DirNode("sub", true, 6, 0, "目录", List.of(txt));
        FileSystem.DirNode newFile = new FileSystem.DirNode("new", false, 8, 1, "普通", List.of());
        FileSystem.DirNode aa = new FileSystem.DirNode("aa", true, 5, 0, "目录",
                extra ? List.of(sub, newFile) : List.of(sub));
        FileSystem.DirNode bb = new FileSystem.DirNode("bb", true, 9, 0, "目录", List.of(sub));
        return List.of(aa, bb);
    }

    private static TreePath path(DirectoryTree tree, String... names) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (String name : names) {
            DefaultMutableTreeNode found = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (child.toString().startsWith(name + "/") || child.toString().startsWith(name + "  (")) {
                    found = child;
                    break;
                }
            }
            if (found == null) {
                throw new AssertionError("Missing tree entry: " + name);
            }
            node = found;
        }
        return new TreePath(node.getPath());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
