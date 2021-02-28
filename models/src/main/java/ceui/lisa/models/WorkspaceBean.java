package ceui.lisa.models;

import java.io.Serializable;

public class WorkspaceBean implements Serializable {
    /**
     * pc :
     * monitor :
     * tool :
     * scanner :
     * tablet :
     * mouse :
     * printer :
     * desktop :
     * music :
     * desk :
     * chair :
     * comment :
     * workspace_image_url : null
     */

    private String pc;
    private String monitor;
    private String tool;
    private String scanner;
    private String tablet;
    private String mouse;
    private String printer;
    private String desktop;
    private String music;
    private String desk;
    private String chair;
    private String comment;
    private String workspace_image_url;

    public String getPc() {
        return pc;
    }

    public void setPc(String pc) {
        this.pc = pc;
    }

    public String getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = monitor;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getScanner() {
        return scanner;
    }

    public void setScanner(String scanner) {
        this.scanner = scanner;
    }

    public String getTablet() {
        return tablet;
    }

    public void setTablet(String tablet) {
        this.tablet = tablet;
    }

    public String getMouse() {
        return mouse;
    }

    public void setMouse(String mouse) {
        this.mouse = mouse;
    }

    public String getPrinter() {
        return printer;
    }

    public void setPrinter(String printer) {
        this.printer = printer;
    }

    public String getDesktop() {
        return desktop;
    }

    public void setDesktop(String desktop) {
        this.desktop = desktop;
    }

    public String getMusic() {
        return music;
    }

    public void setMusic(String music) {
        this.music = music;
    }

    public String getDesk() {
        return desk;
    }

    public void setDesk(String desk) {
        this.desk = desk;
    }

    public String getChair() {
        return chair;
    }

    public void setChair(String chair) {
        this.chair = chair;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getWorkspace_image_url() {
        return workspace_image_url;
    }

    public void setWorkspace_image_url(String workspace_image_url) {
        this.workspace_image_url = workspace_image_url;
    }
}
