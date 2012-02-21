package net.llinux.idea;

import java.util.*;

import org.apache.log4j.*;

import com.intellij.lang.*;
import com.intellij.util.*;

import com.intellij.psi.*;
import com.intellij.psi.javadoc.*;
import com.intellij.psi.codeStyle.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.*;

import com.intellij.psi.impl.*;
import com.intellij.psi.impl.source.*;
import com.intellij.psi.impl.source.tree.*;

import com.intellij.openapi.vfs.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.module.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.actionSystem.*;

import com.intellij.ide.highlighter.*;

/**
 * @author Roman Kosenko <madkite@gmail.com>
 */
public class GenerateMainAction extends AnAction {
	private final Logger log = Logger.getLogger(getClass());

	@Override
	public void update(AnActionEvent event) {
		final PsiFile psiFile = event.getData(DataKeys.PSI_FILE);
		event.getPresentation().setEnabled(psiFile instanceof PsiJavaFile && psiFile.getFileType() instanceof JavaFileType);
	}

	private static final String MAIN_CLASS = "Main";
	public void actionPerformed(AnActionEvent event) {
		actionPerformed(event, MAIN_CLASS);
	}
	public void actionPerformed(AnActionEvent event, final String mainClass) {
		final Project project = event.getData(DataKeys.PROJECT);
		if(project == null)
			return;
		final Module module = event.getData(DataKeys.MODULE);
		if(module == null)
			return;
		final VirtualFile[] roots = ModuleRootManager.getInstance(module).getSourceRoots();
		if(roots.length == 0)
			return;
		final PsiDirectory root = PsiManager.getInstance(project).findDirectory(roots[0]);
		if(root == null)
			return;
		final PsiFile psiFile = event.getData(DataKeys.PSI_FILE);
		if(!(psiFile instanceof PsiJavaFile))
			return;
		final VirtualFile virtualFile = psiFile.getVirtualFile();
		if(virtualFile == null)
			return;
		final PsiJavaFile psiJavaFile = (PsiJavaFile)psiFile;
		if(!psiJavaFile.getFileType().getName().equals("JAVA"))
			return;
		log.info("copying " + psiFile.getName() + " to " + roots[0].getPath());
		ApplicationManager.getApplication().runWriteAction(new Runnable() {
			private final String mainJava = mainClass + ".java";
			public void run() {
				PsiFile main = root.findFile(mainJava);
				if(main != null) {
					if(main.equals(psiJavaFile)) {
						eliminateUnusedCode(psiJavaFile);
						return;
					}
					main.delete();
				}
				PsiJavaFile newFile = (PsiJavaFile)root.copyFileFrom(mainJava, psiJavaFile);
				newFile.setPackageName("");
				String originalFileName = virtualFile.getNameWithoutExtension();
				for(PsiClass psiClass : newFile.getClasses()) {
					PsiModifierList psiModifierList = psiClass.getModifierList();
					if(psiModifierList != null && psiModifierList.hasModifierProperty("public")) {
						if(!psiClass.getName().equals(originalFileName))
							log.warn("incorrect public class name in " + virtualFile);
						rename(psiClass, mainClass);
						break;
					} else if(psiClass.getName().equals(originalFileName)) {
						log.warn("class " + originalFileName + " should be public");
						if(psiModifierList != null)
							psiModifierList.setModifierProperty("public", true);
						rename(psiClass, mainClass);
						break;
					}
				}
				PsiElement first = newFile.findElementAt(0);
				if(first != null && first instanceof PsiWhiteSpace)
					first.delete();

				integrateDependencies(newFile, module.getModuleWithDependenciesScope());
				eliminateUnusedCode(newFile);
				JavaCodeStyleManager.getInstance(project).optimizeImports(newFile);

				Document document = PsiDocumentManager.getInstance(project).getDocument(newFile);
				if(document == null)
					return;
				FileDocumentManager.getInstance().saveDocument(document);

				VirtualFile virtualFile = newFile.getVirtualFile();
				if(virtualFile == null)
					return;
				FileEditorManager.getInstance(project).openFile(virtualFile, true);
			}
		});
	}

	private void rename(final PsiClass psiClass, String newName) {
		log.info("renaming " + psiClass.getName() + "->" + newName);
		final List<PsiJavaCodeReferenceElement> selfReferences = new ArrayList<PsiJavaCodeReferenceElement>();
		psiClass.accept(new JavaRecursiveElementWalkingVisitor() {
			@Override
			public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
				PsiElement targetClass = reference.resolve();
				if(psiClass.equals(targetClass))
					selfReferences.add(reference);
			}
		});
		psiClass.setName(newName);
		for(PsiJavaCodeReferenceElement reference : selfReferences)
			reference.handleElementRename(newName);
	}

	private static PsiWhiteSpace createWhiteSpaceElement(final PsiManager psiManager, final String text) {
		final FileElement holderElement = DummyHolderFactory.createHolder(psiManager, null).getTreeElement();
		final LeafElement newElement = ASTFactory.leaf(TokenType.WHITE_SPACE, holderElement.getCharTable().intern(text));
		holderElement.rawAddChildren(newElement);
		final PsiElement result = newElement.getPsi();
		((TreeElement)result.getNode()).acceptTree(new GeneratedMarkerVisitor());
		return (PsiWhiteSpace)result;
	}

	private void integrateDependencies(final PsiJavaFile javaFile) {
		integrateDependencies(javaFile, GlobalSearchScope.projectScope(javaFile.getProject()));
	}
	private void integrateDependencies(final PsiJavaFile javaFile, final GlobalSearchScope searchScope) {
		final PsiImportList mainImportList = javaFile.getImportList();
		if(mainImportList == null)
			return;
		log.info("integrating dependencies...");
		//noinspection ConstantConditions
		new Runnable() {
			private final Project project = javaFile.getProject();
			private final GlobalSearchScope fileSearchScope = GlobalSearchScope.fileScope(javaFile);
			private Set<String> classes = new HashSet<String>(), imports = new HashSet<String>();
			private void processClass(PsiClass psiClass) {
				if(classes.contains(psiClass.getName()))
					return;
				log.debug("checking " + psiClass.getQualifiedName());
				if(ReferencesSearch.search(psiClass, fileSearchScope).findFirst() != null) {
					PsiFile file = psiClass.getContainingFile();
					if(file instanceof PsiJavaFile)
						processFile((PsiJavaFile)file);
				}
			}
			private void processPackage(PsiPackage psiPackage) {
				PsiClass[] psiClasses = psiPackage.getClasses(searchScope);
				for(PsiClass psiClass : psiClasses)
					processClass(psiClass);
			}
			private void integrate(final PsiClass psiClass) {
				log.info("integrating " + psiClass.getQualifiedName());
				PsiClass newClass = (PsiClass)psiClass.copy();
				PsiModifierList psiModifierList = newClass.getModifierList();
				if(psiModifierList != null && psiModifierList.hasModifierProperty("public"))
					psiModifierList.setModifierProperty("public", false);
				PsiDocComment javadoc = newClass.getDocComment();
				if(javadoc != null)
					javadoc.delete();

				//PsiElement newLine = psiElementFactory.createWhiteSpaceFromText("\n");
				PsiElement newLine = createWhiteSpaceElement(PsiManager.getInstance(project), "\n");
				PsiElement[] children = javaFile.getChildren();
				if(children.length > 0 && !(children[children.length - 1] instanceof PsiWhiteSpace))
					javaFile.add(newLine);
				javaFile.add(newClass);
				javaFile.add(newLine);

				newClass = facade.findClass(newClass.getName(), fileSearchScope);
				if(newClass == null)
					return;
				final List<PsiJavaCodeReferenceElement> selfReferences = new ArrayList<PsiJavaCodeReferenceElement>();
				newClass.accept(new JavaRecursiveElementWalkingVisitor() {
					@Override
					public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
						PsiElement targetClass = reference.resolve();
						if(targetClass != null && targetClass.equals(psiClass))
							selfReferences.add(reference);
					}
				});
				if(!selfReferences.isEmpty()) {
					PsiJavaCodeReferenceElement newClassReferenceElement = null, newReferenceExpression = null;
					for(PsiJavaCodeReferenceElement reference : selfReferences) {
						PsiJavaCodeReferenceElement newReference;
						if(reference instanceof PsiReferenceExpression) {
							if(newReferenceExpression == null)
								newReferenceExpression = psiElementFactory.createReferenceExpression(newClass);
							newReference = newReferenceExpression;
						} else {
							if(newClassReferenceElement == null)
								newClassReferenceElement = psiElementFactory.createClassReferenceElement(newClass);
							newReference = newClassReferenceElement;
						}
						reference.replace(newReference);
					}
				}
			}
			private final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
			private final PsiElementFactory psiElementFactory = facade.getElementFactory();
			private void processFile(PsiJavaFile inlineFile) {
				log.info("processing " + inlineFile.getName());
				for(PsiClass psiClass : inlineFile.getClasses()) {
					if(!classes.add(psiClass.getName()))
						continue;
					integrate(psiClass);
				}
				{
					PsiPackage implicitPackage = facade.findPackage(inlineFile.getPackageName());
					processPackage(implicitPackage);
				}
				PsiImportList importList = inlineFile.getImportList();
				if(importList == null)
					return;
				for(PsiImportStatement importStatement : importList.getImportStatements()) {
					String qualifiedName = importStatement.getQualifiedName();
					if(qualifiedName == null)
						continue;
					PsiElement importElement = importStatement.resolve();
					if(importStatement.isOnDemand()) {
						if(!(importElement instanceof PsiPackage))
							continue;
						PsiPackage psiPackage = (PsiPackage)importElement;
						if(qualifiedName.startsWith("java.") || psiPackage.getDirectories(searchScope).length == 0) {
							if(imports.add(qualifiedName + ".*") && inlineFile != javaFile)
								mainImportList.add(importStatement);
							continue;
						}
						processPackage(psiPackage);
						if(inlineFile == javaFile)
							importStatement.delete();
					} else {
						if(!(importElement instanceof PsiClass))
							continue;
						PsiClass psiClass = (PsiClass)importElement;
						if(qualifiedName.startsWith("java.") || !searchScope.contains(psiClass.getContainingFile().getVirtualFile())) {
							if(imports.add(qualifiedName) && inlineFile != javaFile)
								mainImportList.add(importStatement);
							continue;
						}
						if(ReferencesSearch.search(psiClass, fileSearchScope).findFirst() != null)
							processClass(psiClass);
						if(inlineFile == javaFile)
							importStatement.delete();
					}
				}
				for(PsiImportStaticStatement importStatement : importList.getImportStaticStatements()) {
					PsiClass psiClass = importStatement.resolveTargetClass();
					if(psiClass == null)
						continue;
					String qualifiedName = psiClass.getQualifiedName();
					if(qualifiedName == null)
						continue;
					if(qualifiedName.startsWith("java.") || !searchScope.contains(psiClass.getContainingFile().getVirtualFile())) {
						if(importStatement.isOnDemand())
							qualifiedName += ".*";
						if(imports.add("static " + qualifiedName) && inlineFile != javaFile)
							mainImportList.add(importStatement);
						continue;
					}
					if(ReferencesSearch.search(psiClass, fileSearchScope).findFirst() != null)
						processClass(psiClass);
					if(inlineFile == javaFile)
						importStatement.delete();
				}
			}
			public void run() {
				PsiManager psiManager = PsiManager.getInstance(project);
				psiManager.startBatchFilesProcessingMode();
				try {
					for(PsiClass psiClass : javaFile.getClasses())
						classes.add(psiClass.getName());
					processFile(javaFile);

					log.info("checking " + javaFile.getName());
					final Set<PsiJavaCodeReferenceElement> references = new HashSet<PsiJavaCodeReferenceElement>();
					for(;;) {
						final Set<PsiClass> set = new HashSet<PsiClass>();
						javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
							@Override
							public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
								PsiElement targetElement = reference.resolve();
								if(targetElement instanceof PsiClass) {
									PsiClass targetClass = (PsiClass)targetElement;
									String qualifiedName = targetClass.getQualifiedName();
									if(qualifiedName != null && !qualifiedName.startsWith("java.")) {
										PsiFile file = targetClass.getContainingFile();
										if(file != null && !file.equals(javaFile) && searchScope.contains(file.getVirtualFile())) {
											if(!classes.contains(targetClass.getName()))
												set.add(targetClass);
											references.add(reference);
										}
									}
								}
							}
						});
						if(set.isEmpty())
							break;
						log.info("processing " + set);
						for(PsiClass psiClass : set)
							processClass(psiClass);
					}
					for(PsiJavaCodeReferenceElement reference : references) {
						PsiClass targetClass = (PsiClass)reference.resolve();
						if(targetClass == null)
							continue;
						log.info("fixing " + targetClass.getQualifiedName());
						PsiClass newClass = facade.findClass(targetClass.getName(), fileSearchScope);
						if(newClass == null) {
							log.warn("cannot fix reference to " + targetClass.getQualifiedName());
							continue;
						}
						if(reference instanceof PsiReferenceExpression)
							reference.replace(psiElementFactory.createReferenceExpression(newClass));
						else
							reference.replace(psiElementFactory.createClassReferenceElement(newClass));
					}

					if(javaFile.getClasses().length != classes.size()) {
						List<String> problemClasses = new ArrayList<String>();
						for(String className : classes) {
							if(facade.findClass(className, fileSearchScope) == null)
								problemClasses.add(className);
						}
						log.warn("cannot integrate " + problemClasses);
					}
				} finally {
					psiManager.finishBatchFilesProcessingMode();
				}
			}
		}.run();
	}

	private void eliminateUnusedCode(PsiJavaFile psiFile) {
		log.info("eliminating unused code...");
		final GlobalSearchScope fileSearchScope = GlobalSearchScope.fileScope(psiFile);
		for(;;) {
			final List<PsiElement> unused = new ArrayList<PsiElement>();
			psiFile.acceptChildren(new PsiElementVisitor() {
				private boolean isUsed(PsiElement element) {
					try {
						for(PsiReference reference : ReferencesSearch.search(element, fileSearchScope)) {
							for(PsiElement referenceElement = reference.getElement(); referenceElement != element; referenceElement = referenceElement.getParent()) {
								if(referenceElement == null)
									return true;
							}
						}
					} catch(Throwable t) {
						log.warn("error discovering " + element, t);
						return true;
					}
					return false;
				}
				private boolean leave(PsiJavaCodeReferenceElement reference) {
					if(reference == null)
						return true;
					PsiElement element = reference.resolve();
					if(!(element instanceof PsiClass))
						return true;
					PsiClass clazz = (PsiClass)element;
					String fullName = clazz.getQualifiedName();
					//noinspection RedundantIfStatement
					if(fullName.startsWith("java.util."))
						return false;
					return true;
				}
				@Override
				public void visitElement(PsiElement element) {
					if(element instanceof PsiField) {
						PsiField field = (PsiField)element;
						PsiExpression initializer = field.getInitializer();
						if(initializer instanceof PsiMethodCallExpression) {
							PsiElement method = ((PsiMethodCallExpression)initializer).getMethodExpression().resolve();
							if(!(method instanceof PsiMethod && ((PsiMethod)method).getName().startsWith("get")))
								return;
						}
						PsiType type = field.getType();
						if(initializer instanceof PsiNewExpression && !(type instanceof PsiArrayType)
							&& (!(type instanceof PsiClassReferenceType) || leave(((PsiClassReferenceType)type).getReference())))
							return;
						if(!isUsed(element))
							unused.add(element);
					} else if(element instanceof PsiMethod) {
						PsiMethod method = (PsiMethod)element;
						if(method.findSuperMethods().length != 0)
							return;
						PsiModifierList methodModifierList = method.getModifierList();
						if(method.getName().equals("main") && methodModifierList.hasModifierProperty("public") && methodModifierList.hasModifierProperty("static")) {
							PsiElement parent = method.getParent();
							if(parent instanceof PsiClass) {
								PsiModifierList classModifierList = ((PsiClass)parent).getModifierList();
								if(classModifierList != null && classModifierList.hasModifierProperty("public"))
									return;
							}
						}
						if(!isUsed(element))
							unused.add(element);
					} else if(element instanceof PsiClass) {
						PsiModifierList psiModifierList = ((PsiClass)element).getModifierList();
						if(!(psiModifierList != null && psiModifierList.hasModifierProperty("public")) && !isUsed(element))
							unused.add(element);
						else
							element.acceptChildren(this);
					} if(element instanceof PsiJavaFile)
						element.acceptChildren(this);
				}
			});
			int count = 0;
			for(PsiElement element : unused) {
				log.info("unused element: " + element.getParent() + "/" + element);
				if(element.isValid()) {
					try {
						PsiElement last = null, parent = element.getParent();
						if(parent instanceof PsiClass) {
							PsiElement[] children = parent.getChildren();
							if(children.length >= 3 && children[children.length - 1] instanceof PsiJavaToken) {
								if(children[children.length - 3] == element && children[children.length - 2] instanceof PsiWhiteSpace)
									last = children[children.length - 2].copy();
								else if(children[children.length - 2] == element && children[children.length - 3] instanceof PsiWhiteSpace)
									children[children.length - 3].delete();
							}
						}
						if(element instanceof PsiField)
							((PsiField)element).normalizeDeclaration();
						element.delete();
						count++;
						if(last != null) {
							PsiElement[] children = parent.getChildren();
							if(children.length >= 1 && children[children.length - 1] instanceof PsiJavaToken) {
								if(children.length >= 2 && children[children.length - 2] instanceof PsiWhiteSpace)
									children[children.length - 2].replace(last);
								else
									parent.addBefore(last, children[children.length - 1]);
							}
						}
					} catch(IncorrectOperationException e) {
						log.error("deleting " + element, e);
					}
				} else
					log.warn(element + " is not valid");
			}
			if(count == 0)
				break;
		}
	}
}
