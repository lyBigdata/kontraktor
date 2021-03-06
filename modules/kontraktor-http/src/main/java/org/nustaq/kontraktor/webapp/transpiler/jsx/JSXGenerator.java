package org.nustaq.kontraktor.webapp.transpiler.jsx;

import org.nustaq.kontraktor.webapp.npm.JNPMConfig;
import org.nustaq.kontraktor.webapp.transpiler.ErrorHandler;
import org.nustaq.utils.FileUtil;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JSXGenerator {

    String transformFunctionName;

    public JSXGenerator() {
        this("React.createElement");
    }

    public JSXGenerator(String transformFunctionName) {
        this.transformFunctionName = transformFunctionName;
    }

    public void generateJS(TokenNode root, PrintStream out ) {
        for (int i = 0; i < root.getChildren().size(); i++) {
            TokenNode tokenNode = root.getChildren().get(i);
            String s = tokenNode.getChars().toString().trim();
            if ( s.startsWith("/*") && s.endsWith("*/") )
                out.print("null");
            else
                renderSingleNode(tokenNode, out);
        }
    }
    protected void renderSingleNode(TokenNode tokenNode, PrintStream out) {
        renderSingleNode(tokenNode,out,false);
    }
    protected void renderSingleNode(TokenNode tokenNode, PrintStream out, boolean quote) {
        if ( tokenNode instanceof JSNode) {
            List<TokenNode> chs = tokenNode.getChildren();
            if ( chs.size() > 0 ) {
                // note: code below assumes '{ ... }' and eliminates braces
                // fails for spread operator
                // check for direct sprd

                if ( JSXParser.SHIM_OBJ_SPREAD &&
                     chs.get(0) instanceof ContentNode &&
                    chs.get(0).getChars().length() > 0 &&
                    chs.get(0).getChars().charAt(0) == '_') {
                   // nothing
                } else {
                    // cut braces
                    if (chs.get(0) instanceof ContentNode)
                        ((ContentNode) chs.get(0)).getChars().setCharAt(0, ' ');
                    if (chs.get(chs.size() - 1) instanceof ContentNode) {
                        StringBuilder chars = ((ContentNode) chs.get(chs.size() - 1)).getChars();
                        chars.setCharAt(chars.length() - 1, ' ');
                    }
                }
            }
            generateJS(tokenNode,out);
        } else if ( tokenNode instanceof ContentNode) {
            if (quote)
                out.print(quoteJSString(tokenNode.getChars()));
            else
                out.print(tokenNode.getChars());
        } else if ( tokenNode instanceof TagNode) {
            TagNode te = (TagNode) tokenNode;
            out.println(transformFunctionName+"(");
            if ( te.isReactComponent() ) {
                out.println("  "+te.getTagName()+",");
            } else {
                out.println("  '"+te.getTagName()+"',");
            }
            if ( te.getAttributes().size() == 0 ) {
                out.println("  null"+(te.getChildren().size()>0?",":""));
            } else {
                boolean hasSpread = false;
                if ( JSXParser.SHIM_OBJ_SPREAD )
                    hasSpread = te.hasSprdInAttributes();
                // render attributes
                if ( hasSpread )
                    out.println( "    _sprd({");
                else
                    out.println( "    {");
                int cnt = 0;
                for (int j = 0; j < te.getAttributes().size(); j++) {
                    AttributeNode ae = te.getAttributes().get(j);
                    if ( ae.getName().charAt(0) == '_' && "_JS_".equals(ae.getName().toString()) ) {
                        // top level js like <JSX {...props}/>
                        out.print("    '..."+(cnt++)+"':");
                    } else
                        out.print("    '"+ae.getName().toString()+"':");
                    if ( ae.isJSValue() ) {
                        generateJS(ae,out);
                    } else {
                        if (ae.getValue() != null ){
                            out.print(ae.getValue());
                        } else
                            out.print("true");
                    }
                    if ( j < te.getAttributes().size()-1 )
                        out.println(",");
                    else
                        out.println("");
                }
                if ( hasSpread )
                    out.println( "  })"+(te.getChildren().size()>0?",":""));
                else
                    out.println( "  }"+(te.getChildren().size()>0?",":""));
            }
            boolean nonEmptyWasThere = false;
            for (int j = 0; j < te.getChildren().size(); j++) {
                TokenNode entry = te.getChildren().get(j);
                if ( entry instanceof ContentNode) {
                    ContentNode ce = (ContentNode) entry;
                    ce.trim();
                    if ( ! ce.isEmpty() ) {
                        if ( nonEmptyWasThere )
                            out.println(",");
                        nonEmptyWasThere = true;
                        out.print("'");
                        renderSingleNode(entry, out, true);
                        out.print("'");
                    }
                } else {
                    if ( nonEmptyWasThere )
                        out.println(",");
                    nonEmptyWasThere = true;
                    renderSingleNode(entry, out);
                }
            }
            out.print(")");
        } else {
            System.out.println("UNKNOWN NODE "+ tokenNode);
        }
    }

    private String quoteJSString(StringBuilder value) {
        String s = value.toString();
        s = s.replace("\"","\\\"");
        s = s.replace("'","\\'");
        return s;
    }

    public static String camelCase(String name) {
        int i = name.indexOf("-");
        if ( i > 0) {
            return camelCase(name.substring(0,i)+Character.toUpperCase(name.charAt(i+1))+name.substring(i+2));
        }
        return name;
    }

    public static class ParseResult {
        File f;
        byte[] filedata;
        List<ImportSpec> imports;
        List<String> globals;
        Set<String> ignoredRequires;
        String extension;
        String defaultExport;

        public ParseResult(File f, byte[] filedata, String extension, List<ImportSpec> imports, List<String> globals, Set<String> ignoredReq, String defaultExport) {
            this.filedata = filedata;
            this.imports = imports;
            this.globals = globals;
            this.extension = extension;
            this.f = f;
            this.ignoredRequires = ignoredReq;
            this.defaultExport = defaultExport;
        }


        public String getDefaultExport() {
            return defaultExport;
        }

        public Set<String> getIgnoredRequires() {
            return ignoredRequires;
        }

        public String getExtension() {
            return extension;
        }

        public byte[] getFiledata() {
            return filedata;
        }

        public List<ImportSpec> getImports() {
            return imports;
        }

        public List<String> getGlobals() {
            return globals;
        }

        public boolean generateESWrap() {
            // detect es imports, will not be suffiecient, need export check also
            return ! generateCommonJSWrap() && ("jsx".equals(extension) || imports.size() > 0);
        }

        public boolean generateCommonJSWrap() {
            return f.getAbsolutePath().replace('\\','/').indexOf("/node_modules/") >= 0;
        }

        public String getFilePath() {
            return f.getAbsolutePath();
        }

        public String getDir() {
            return f.getParentFile().getAbsolutePath();
        }

        public File getFile() {
            return f;
        }

        public ParseResult patchImports(Map<String, String> nodeLibraryMap) {
            if ( nodeLibraryMap != null && nodeLibraryMap.size() > 0 ) {
                imports.forEach( imp -> {
                    String s = nodeLibraryMap.get(imp.getFrom());
                    if (s!=null) {
                        imp.from(s);
                    }
                });
            }
            return this;
        }
    }

    public static ParseResult process(
        File f, boolean pretty, NodeLibNameResolver nlib, JNPMConfig config ) throws IOException {

        // this is inefficient, there are loads of optimization opportunities,
        // code runs in devmode only ..

        JSXParser jsx = new JSXParser(f,nlib);
        JSNode root = new JSNode();
        byte[] bytes = FileUtil.readFully(f);

        String cont = new String(bytes, "UTF-8");
        jsx.parseJS(root,new Inp(cont,f));
        if ( jsx.depth != 0 ) {
            ErrorHandler.get().add( JSXGenerator.class, "probably parse issues with non-matching braces",f);
            ParseResult parseResult = new ParseResult(f, bytes, f.getName().endsWith(".js") ? "js" : "jsx", jsx.getImports(), jsx.getTopLevelObjects(), jsx.getIgnoredRequires(),jsx.getDefaultExport());
            return parseResult.patchImports(config.getNodeLibraryMap());
        }
//        if ( f.getName().indexOf("semanticplay") >= 0 )
//            System.out.println("POK");
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        PrintStream ps = new PrintStream(out);
        JSXGenerator gen = new JSXGenerator(config.getTransformFunction());
        gen.generateJS(root,ps);
        ps.flush();ps.close();
        byte[] filedata = out.toByteArray();
        if (pretty) {
            ByteArrayOutputStream outpretty = new ByteArrayOutputStream(filedata.length + filedata.length / 5);
            PrintStream pspretty = new PrintStream(outpretty);
            JSBeautifier beautifier = new JSBeautifier();
            beautifier.parseJS(new GenOut(pspretty), new Inp(new String(filedata, "UTF-8"), f));
            pspretty.flush();
            pspretty.close();
            filedata = outpretty.toByteArray();
        }
        ParseResult parseResult = new ParseResult(
            f, filedata, f.getName().endsWith(".js") ? "js" : "jsx", jsx.getImports(),
            jsx.getTopLevelObjects(), jsx.getIgnoredRequires(), jsx.getDefaultExport() );
        parseResult.patchImports(config.getNodeLibraryMap());
        return parseResult;
    }
}
