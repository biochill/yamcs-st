package org.yamcs.http.api.archive;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;
import org.yamcs.http.api.RestDownsampler;
import org.yamcs.http.api.RestDownsampler.Sample;

public class RestDownsamplerTest {

    @Test
    public void testSampling() {
        RestDownsampler sampler = new RestDownsampler(1, 10, 3);

        List<Sample> samples = sampler.collect();
        assertEquals(0, samples.size());

        sampler.process(1, 5);

        samples = sampler.collect();
        assertEquals(1, samples.size());
        assertEquals(5, samples.get(0).avg, 1e-10);
        assertEquals(1, samples.get(0).n);

        // Add to same bucket
        sampler.process(2, 10);
        assertEquals(1, samples.size());
        assertEquals((5 + 10) / 2., samples.get(0).avg, 1e-10);
        assertEquals(2, samples.get(0).n);

        // Add to same bucket
        sampler.process(3, 7);
        samples = sampler.collect();
        assertEquals(1, samples.size());
        assertEquals((5 + 10 + 7) / 3., samples.get(0).avg, 1e-10);
        assertEquals(3, samples.get(0).n);

        // Due to flooring, leads to a new bucket
        sampler.process(4, 2);
        samples = sampler.collect();
        assertEquals(2, samples.size());

        Sample sample0 = samples.get(0);
        assertEquals((5 + 10 + 7) / 3., sample0.avg, 1e-10);
        assertEquals(5, sample0.min, 1e-10);
        assertEquals(10, sample0.max, 1e-10);

        Sample sample1 = samples.get(1);
        assertEquals(2, sample1.avg, 1e-10);
        assertEquals(2, sample1.min, 1e-10);
        assertEquals(2, sample1.max, 1e-10);
    }
    
    @Test
    public void testSamplingTooMany() {
        RestDownsampler sampler = new RestDownsampler(1, 2, 3);
        sampler.process(1, 1);
        sampler.process(2, 2);
        sampler.process(2, 2.3);
        List<Sample> samples = sampler.collect();
        assertEquals(1, samples.size());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testSamplingInvalid() {
       new RestDownsampler(2, 1, 3);
    }
}
