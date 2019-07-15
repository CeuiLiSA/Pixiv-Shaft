package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;

public class PartResponse implements ListShow<PartResponse.ElementBean> {


    /**
     * ret : 0
     * msg : 100
     * element : [{"name":"主板","a_number":"BN94-12345A","id":563133},{"name":"拉拔器","a_number":"123456789","id":562491},{"name":"拉拔器","a_number":"123456789","id":562385},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":562395},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":562289},{"name":"拉拔器","a_number":"123456789","id":562276},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":562180},{"name":"拉拔器","a_number":"123456789","id":562170},{"name":"拉拔器","a_number":"123456789","id":562064},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":562074},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561968},{"name":"拉拔器","a_number":"123456789","id":561958},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561862},{"name":"拉拔器","a_number":"123456789","id":561586},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561490},{"name":"拉拔器","a_number":"123456789","id":561480},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561384},{"name":"拉拔器","a_number":"123456789","id":561374},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561278},{"name":"拉拔器","a_number":"123456789","id":561268},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":561172},{"name":"拉拔器","a_number":"123456789","id":559438},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":559342},{"name":"拉拔器","a_number":"123456789","id":559014},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558918},{"name":"拉拔器","a_number":"123456789","id":558763},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558667},{"name":"拉拔器","a_number":"123456789","id":558657},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558561},{"name":"拉拔器","a_number":"123456789","id":558550},{"name":"拉拔器","a_number":"123456789","id":558444},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558454},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558348},{"name":"拉拔器","a_number":"123456789","id":558332},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558236},{"name":"拉拔器","a_number":"123456789","id":558225},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558129},{"name":"拉拔器","a_number":"123456789","id":558119},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":558023},{"name":"拉拔器","a_number":"123456789","id":558013},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557917},{"name":"拉拔器","a_number":"123456789","id":557907},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557811},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557750},{"name":"拉拔器","a_number":"123456789","id":557740},{"name":"拉拔器","a_number":"123456789","id":557634},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557644},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557538},{"name":"拉拔器","a_number":"123456789","id":557528},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557432},{"name":"拉拔器","a_number":"123456789","id":557422},{"name":"拉拔器","a_number":"123456789","id":557316},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557326},{"name":"拉拔器","a_number":"123456789","id":557210},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557220},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557114},{"name":"拉拔器","a_number":"123456789","id":557104},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":557008},{"name":"拉拔器","a_number":"123456789","id":556998},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556902},{"name":"拉拔器","a_number":"123456789","id":556892},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556796},{"name":"拉拔器","a_number":"123456789","id":556786},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556690},{"name":"拉拔器","a_number":"123456789","id":556680},{"name":"拉拔器","a_number":"123456789","id":556574},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556584},{"name":"拉拔器","a_number":"123456789","id":556468},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556478},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556372},{"name":"拉拔器","a_number":"123456789","id":556362},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556266},{"name":"拉拔器","a_number":"123456789","id":556255},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556159},{"name":"拉拔器","a_number":"123456789","id":556149},{"name":"拉拔器","a_number":"123456789","id":556043},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":556053},{"name":"拉拔器","a_number":"123456789","id":555937},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555947},{"name":"拉拔器","a_number":"123456789","id":555831},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555841},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555735},{"name":"拉拔器","a_number":"123456789","id":555725},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555629},{"name":"拉拔器","a_number":"123456789","id":555619},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555523},{"name":"拉拔器","a_number":"123456789","id":555513},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555417},{"name":"拉拔器","a_number":"123456789","id":555407},{"name":"拉拔器","a_number":"123456789","id":555300},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555310},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555204},{"name":"拉拔器","a_number":"123456789","id":555194},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":555098},{"name":"拉拔器","a_number":"123456789","id":555081},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":554985},{"name":"拉拔器","a_number":"123456789","id":554974},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":554878},{"name":"拉拔器","a_number":"123456789","id":554867},{"name":"R-电磁四通阀SHF(L)-7H-34U(SQ-A2522G-000164)$⋯","a_number":"810223404","id":554771}]
     */

    private int ret;
    private int msg;
    private List<ElementBean> element;

    public int getRet() {
        return ret;
    }

    public void setRet(int ret) {
        this.ret = ret;
    }

    public int getMsg() {
        return msg;
    }

    public void setMsg(int msg) {
        this.msg = msg;
    }

    public List<ElementBean> getElement() {
        return element;
    }

    public void setElement(List<ElementBean> element) {
        this.element = element;
    }

    @Override
    public List<ElementBean> getList() {
        return element;
    }

    @Override
    public String getNextUrl() {
        return null;
    }

    public static class ElementBean {
        /**
         * name : 主板
         * a_number : BN94-12345A
         * id : 563133
         */

        private String name;
        private String a_number;
        private int id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getA_number() {
            return a_number;
        }

        public void setA_number(String a_number) {
            this.a_number = a_number;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }
    }
}
