package sample.mybatis.xml.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sample.mybatis.xml.domain.Hotel;
import sample.mybatis.xml.service.HotelService;

@RestController
@RequestMapping("/rest/hotel")
public class HotelController {

    @Autowired
    private HotelService hotelService;

    @RequestMapping(value = "/query", method = RequestMethod.GET)
    public Hotel fetchHotelByCityId(@RequestParam(value = "cityId") int cityId) {
        return hotelService.selectByCityId(cityId);
    }

}
