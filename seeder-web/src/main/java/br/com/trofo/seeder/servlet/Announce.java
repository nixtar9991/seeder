package br.com.trofo.seeder.servlet;

import br.com.trofo.seeder.dao.PeerDao;
import br.com.trofo.seeder.entity.Peer;
import br.com.trofo.seeder.util.Bencode;
import br.com.trofo.seeder.util.Hash;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 *
 * @author Andoreh
 */
public class Announce extends HttpServlet {

    public static final int INTERVAL = 3600;
    private PeerDao peerDao;

    protected void processRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {

        injectBeans();

        String responseString = "error";
        try {
            Peer requestingPeer = buildRequestingPeer(request);

            Collection<Peer> peers = peerDao.getPeers(requestingPeer, 100);

            responseString = buildCompactResponse(peers);

            persistOrUpdatePeer(requestingPeer);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        writeResponse(response, responseString);
    }

    private int getPort(HttpServletRequest request) throws NumberFormatException {
        String parameter = request.getParameter("port");
        if (parameter != null) {
            return Integer.parseInt(parameter);
        }
        return request.getRemotePort();
    }

    private void writeResponse(HttpServletResponse response, String responseString) throws IOException {
        response.setContentType("text/plain");

        OutputStream out = response.getOutputStream();
        try {
            for (int i = 0; i < responseString.length(); i++) {
                out.write(responseString.charAt(i));
            }
        } finally {
            out.close();
        }
    }

    private void injectBeans() throws BeansException {
        ApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        peerDao = context.getBean(PeerDao.class);
    }

    private void persistOrUpdatePeer(Peer requestingPeer) throws BeansException {
        peerDao.persistEntity(requestingPeer);
    }

    private String buildCompactResponse(Collection<Peer> peers) throws Exception {
        StringBuilder resultIpV6 = new StringBuilder();
        StringBuilder resultIpV4 = new StringBuilder();
        int seeders = 0;
        int leechers = 0;
        for (Peer requestingPeer : peers) {

            if (requestingPeer.isComplete()) {
                seeders++;
            } else {
                leechers++;
            }

            char[] ipBytes = Hash.hexStringToByteArray(requestingPeer.getIp());

            ByteBuffer port = ByteBuffer.allocate(4);
            port.putInt(requestingPeer.getPort());
            if (requestingPeer.getIp().length() == 8) {
                resultIpV4.append(ipBytes);
                resultIpV4.append((char) port.get(2));
                resultIpV4.append((char) port.get(3));
            } else {
                resultIpV6.append(ipBytes);
                resultIpV6.append((char) port.get(2));
                resultIpV6.append((char) port.get(3));
            }

        }

        HashMap responseParams = new HashMap();
        responseParams.put("complete", seeders);
        responseParams.put("incomplete", leechers);
        responseParams.put("interval", INTERVAL);
        responseParams.put("peers", resultIpV4.toString());
        if (resultIpV6.length() > 0) {
            responseParams.put("peers_ipv6", resultIpV6.toString());
        }

        return Bencode.encode(responseParams);
    }

    private Peer buildRequestingPeer(HttpServletRequest request) throws NumberFormatException, UnknownHostException {
        Peer requestingPeer = new Peer();
        requestingPeer.setInfoHash(Hash.getHex(request.getParameter("info_hash").getBytes()));
        requestingPeer.setPort(getPort(request));

        InetAddress remoteAddress = getAddress(request);
        requestingPeer.setIp(Hash.getHex(remoteAddress.getAddress()));

        Date expireTime;
        String event = request.getParameter("event");
        if (event != null && event.equals("stopped")) {
            expireTime = new Date();
        } else {
            expireTime = new Date(System.currentTimeMillis() + (int) (INTERVAL * 1000 * 1.2));
        }
        requestingPeer.setExpires(expireTime);

        if (event != null && event.equals("completed")) {
            requestingPeer.setComplete(true);
        }

        return requestingPeer;
    }

    private InetAddress getAddress(HttpServletRequest request) throws UnknownHostException {
        InetAddress remoteAddress = InetAddress.getByName(getNginxIp(request));
        // check for ip
        if (request.getParameterMap().containsKey("ip")) {
            // is this on the local LAN?
            if (remoteAddress.isSiteLocalAddress()) {
                // honour the ip setting
                remoteAddress = InetAddress.getByName(request.getParameter("ip"));
            }
        }
        return remoteAddress;
    }

    // <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /**
     * Handles the HTTP
     * <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Handles the HTTP
     * <code>POST</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Returns a short description of the servlet.
     */
    @Override
    public String getServletInfo() {
        return "Seeder Tracker";
    }// </editor-fold>

    private String getNginxIp(HttpServletRequest request) {
        String realIP = request.getHeader("X-Real-IP");
        return realIP != null ? realIP : request.getRemoteAddr();
    }
}
